package com.jobradar.core.crawl;

import com.jobradar.core.domain.CrawlSource;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 搜狗微信搜索解析器：研究所/央国企校招公告的「发现层」。
 *
 * <p>背景：军工所/央国企的校招启事大量发布在微信公众号（所官方号/国资小新/国聘等），
 * 探测确认搜狗微信搜索（weixin.sogou.com/weixin?type=2&query=...）匿名稳定可用。
 * 用户真实检索路径「所名字+2027校园招聘」由本解析器自动化。
 *
 * <p>每个源对应一个集团级关键词（如「航天科工 2027校园招聘」），
 * 结果落到具体院所（从标题识别公司：别名表 → 公众号名 → 种子关键词三级兜底）。
 *
 * <p>链接处理：搜狗结果链接是带时效签名的跳转链接，逐条经 PageFetcher 解析为
 * mp.weixin.qq.com 规范地址（限前 5 条解析，控制请求量；其余保留搜狗链接，
 * 并在 jd_text 里附上标题，链接失效后可按标题手动检索）。
 */
@Component
public class SogouWeixinParser implements SiteParser {

    private static final String SEARCH_BASE = "https://weixin.sogou.com";
    private static final Pattern TIME_SCRIPT = Pattern.compile("timeConvert\\('?(\\d{9,11})'?\\)");
    /** 每次解析最多跳转解析的链接数（控制对搜狗的请求量） */
    private static final int MAX_RESOLVE = 5;

    private final PageFetcher fetcher;
    private final CompanyAliases companyAliases;

    public SogouWeixinParser(PageFetcher fetcher, CompanyAliases companyAliases) {
        this.fetcher = fetcher;
        this.companyAliases = companyAliases;
    }

    @Override
    public String name() {
        return "sogou-weixin";
    }

    @Override
    public List<RawJobPosting> parse(CrawlSource source, String html) {
        Document doc = Jsoup.parse(html, SEARCH_BASE);
        String seedKeyword = readSeedKeyword(source);
        List<RawJobPosting> out = new ArrayList<>();
        int resolved = 0;

        for (Element li : doc.select("ul.news-list > li")) {
            Element titleEl = li.selectFirst("h3 a");
            if (titleEl == null || titleEl.text().isBlank()) {
                continue;
            }
            String title = titleEl.text().trim();
            String href = titleEl.attr("abs:href"); // Jsoup 按 baseUri 绝对化
            if (href.isBlank()) {
                href = titleEl.attr("href");
            }
            String summary = li.selectFirst("p.txt-info") != null
                    ? li.selectFirst("p.txt-info").text().trim() : "";
            Element accountEl = li.selectFirst("div.s-p a");
            String account = accountEl != null ? accountEl.text().trim() : "";
            String date = extractDate(li.html());

            String company = identifyCompany(title, account, seedKeyword);

            // 跳转解析（限量）：把时效链接换成规范 mp 链接
            String finalUrl = null;
            if (!href.isBlank() && resolved < MAX_RESOLVE) {
                finalUrl = fetcher.resolveFinalUrl(href);
                resolved++;
            }

            String jd = "【微信公众号文章】来源：" + (account.isBlank() ? "未知公众号" : account)
                    + (summary.isBlank() ? "" : "\n摘要：" + summary)
                    + "\n若链接失效，请按标题搜索原文：" + title;

            out.add(new RawJobPosting(
                    company, title, null, null, date, null,
                    finalUrl != null ? finalUrl : href, jd));
        }
        return out;
    }

    /**
     * 公司识别三级兜底：① 标题命中别名表 → 标准名；② 公众号名出现在标题里
     * （说明是院所官方号发自家公告）→ 公众号名；③ 种子关键词（集团名）。
     * 顺序有讲究：「国资小新」这类转载号的名字不在公告标题里，不能被误认为雇主。
     */
    private String identifyCompany(String title, String account, String seedKeyword) {
        String fromTitle = companyAliases.findIn(title);
        if (fromTitle != null) {
            return fromTitle;
        }
        if (account != null && !account.isBlank() && title.contains(account)) {
            return account;
        }
        return seedKeyword != null ? seedKeyword : "未知";
    }

    /** 发布时间在 timeConvert 脚本里（Unix 秒） */
    private static String extractDate(String liHtml) {
        Matcher m = TIME_SCRIPT.matcher(liHtml);
        if (!m.find()) {
            return null;
        }
        return LocalDate.ofInstant(Instant.ofEpochSecond(Long.parseLong(m.group(1))),
                ZoneId.of("Asia/Shanghai")).toString();
    }

    private static String readSeedKeyword(CrawlSource source) {
        try {
            var meta = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(source.getMeta() == null || source.getMeta().isBlank() ? "{}" : source.getMeta());
            String query = meta.path("request").path("params").path("query").asText(null);
            // query 形如「航天科工 2027校园招聘」→ 取第一个词作为集团种子名
            return query == null ? null : query.split("\\s+")[0];
        } catch (Exception e) {
            return null;
        }
    }
}
