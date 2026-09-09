package com.jobradar.core.crawl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobradar.core.domain.CrawlSource;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

/**
 * 页面抓取器（Jsoup）：UA 伪装 + 超时 + 有限重试。
 *
 * <p>两种抓取形态（W3-2 站点适配落地）：
 * <ul>
 *   <li><b>静态页 GET</b>：meta 无 request 节，直接 GET source.url
 *       （selector-list 解析器的目标站，如运营商/银行官网）；</li>
 *   <li><b>JSON API</b>：meta.request 描述请求方法/参数/分页参数名，
 *       支持 POST form（牛客 square-search 实测匿名可用）与 POST JSON，
 *       分页由 {@link CrawlerService} 按 meta.pagination 循环驱动。</li>
 * </ul>
 *
 * <p>meta.request 结构：
 * <pre>{@code
 * "request": {
 *   "method": "POST",                        // 缺省 GET
 *   "contentType": "form",                   // form(缺省) | json
 *   "params": {"query": "大模型", "recruitType": "1"},
 *   "headers": {"Origin": "https://www.nowcoder.com"}
 * },
 * "pagination": {"pageParam": "page", "start": 1, "pages": 3}
 * }</pre>
 *
 * <p>合规边界（crawler-engineering 学习点）：
 * <ul>
 *   <li>单用户低频使用（每日一轮），不带并发、不带代理池——不触碰反爬红线；</li>
 *   <li>重试仅 2 次且指数退避，目标站 5xx/网络抖动时自愈，4xx 不重试
 *       （403/404 重试无意义还显得像攻击）；</li>
 *   <li>失败抛 IOException 由管线按源隔离。</li>
 * </ul>
 */
@Component
public class PageFetcher {

    private static final Logger log = LoggerFactory.getLogger(PageFetcher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_ATTEMPTS = 3;
    static final String DEFAULT_PAGE_PARAM = "page";

    /** 抓取页面 HTML（静态站直取） */
    public String fetch(String url) throws IOException {
        return executeWithRetry(buildGet(url, -1, null));
    }

    /**
     * 按源配置抓取一页。page < 0 表示不分页（静态页）；
     * page >= 0 时按 meta.pagination.pageParam 注入页码。
     */
    public String fetch(CrawlSource source, int page) throws IOException {
        return executeWithRetry(buildRequest(source, page));
    }

    /** 从 meta 构造一次请求的 Connection（抽成静态便于单测，不触网） */
    static Connection buildRequest(CrawlSource source, int page) {
        JsonNode meta = readMeta(source);
        JsonNode request = meta.get("request");
        JsonNode pagination = meta.get("pagination");
        String pageParam = pagination != null && pagination.get("pageParam") != null
                ? pagination.get("pageParam").asText() : DEFAULT_PAGE_PARAM;

        if (request == null || !request.isObject()) {
            // 无 request 节：GET 静态页；配了 pagination 就把页码拼到 query
            return buildGet(source.getUrl(), page, page >= 0 && pagination != null ? pageParam : null);
        }

        String method = text(request, "method", "GET");
        Connection conn = Jsoup.connect(source.getUrl())
                .userAgent(USER_AGENT)
                .timeout(TIMEOUT_MS)
                .followRedirects(true)
                .ignoreContentType(true) // JSON API：Jsoup 默认只接 text/*，必须放开
                .method("POST".equalsIgnoreCase(method) ? Connection.Method.POST : Connection.Method.GET);

        JsonNode headers = request.get("headers");
        if (headers != null && headers.isObject()) {
            headers.fields().forEachRemaining(h -> conn.header(h.getKey(), h.getValue().asText()));
        }

        JsonNode params = request.get("params");
        boolean json = "json".equalsIgnoreCase(text(request, "contentType", "form"));
        if (json) {
            ObjectNode body = MAPPER.createObjectNode();
            if (params != null && params.isObject()) {
                params.fields().forEachRemaining(f -> body.put(f.getKey(), f.getValue().asText()));
            }
            if (page >= 0) {
                body.put(pageParam, page);
            }
            conn.header("Content-Type", "application/json")
                    .requestBody(body.toString());
        } else {
            if (params != null && params.isObject()) {
                for (Iterator<Map.Entry<String, JsonNode>> it = params.fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> f = it.next();
                    conn.data(f.getKey(), f.getValue().asText());
                }
            }
            if (page >= 0) {
                conn.data(pageParam, String.valueOf(page));
            }
        }
        return conn;
    }

    private static Connection buildGet(String url, int page, String pageParam) {
        Connection conn = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(TIMEOUT_MS)
                .followRedirects(true);
        if (page >= 0 && pageParam != null) {
            conn.data(pageParam, String.valueOf(page));
        }
        return conn;
    }

    private String executeWithRetry(Connection conn) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                Connection.Response resp = conn.execute();
                int status = resp.statusCode();
                if (status >= 400 && status < 500) {
                    // 4xx 不重试：目标站明确拒绝，重试只会更像攻击
                    throw new IOException("HTTP " + status + "（客户端错误，不重试）");
                }
                return resp.body();
            } catch (IOException e) {
                last = e;
                log.warn("抓取 {} 第 {} 次失败: {}", conn.request().url(), attempt, e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    sleepQuietly(1000L * attempt); // 指数退避：1s, 2s
                }
            }
        }
        throw last;
    }

    private static JsonNode readMeta(CrawlSource source) {
        try {
            return MAPPER.readTree(source.getMeta() == null || source.getMeta().isBlank()
                    ? "{}" : source.getMeta());
        } catch (Exception e) {
            throw new IllegalStateException("源「" + source.getName() + "」meta JSON 解析失败: " + e.getMessage(), e);
        }
    }

    private static String text(JsonNode node, String key, String fallback) {
        JsonNode n = node.get(key);
        return n != null && n.isTextual() && !n.asText().isBlank() ? n.asText() : fallback;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
