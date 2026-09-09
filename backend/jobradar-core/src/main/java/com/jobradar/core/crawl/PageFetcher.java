package com.jobradar.core.crawl;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 页面抓取器（Jsoup）：UA 伪装 + 超时 + 有限重试。
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

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_ATTEMPTS = 3;

    /** 抓取页面 HTML。可注入替身便于单测（不设接口——本地项目，类继承足够） */
    public String fetch(String url) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                Connection.Response resp = Jsoup.connect(url)
                        .userAgent(USER_AGENT)
                        .timeout(TIMEOUT_MS)
                        .followRedirects(true)
                        .execute();
                int status = resp.statusCode();
                if (status >= 400 && status < 500) {
                    // 4xx 不重试：目标站明确拒绝，重试只会更像攻击
                    throw new IOException("HTTP " + status + "（客户端错误，不重试）");
                }
                return resp.body();
            } catch (IOException e) {
                last = e;
                log.warn("抓取 {} 第 {} 次失败: {}", url, attempt, e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    sleepQuietly(1000L * attempt); // 指数退避：1s, 2s
                }
            }
        }
        throw last;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
