package com.jobradar.core.crawl;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 文章正文提取器：给 ingest 的「粘贴链接」模式用。
 *
 * <p>场景：手机上看到公众号/官网的岗位文章，复制链接提交，服务端抓取正文送 LLM 解析。
 * 与爬虫管线的区别：这是用户触发的单篇抓取（行为等价于用户自己打开页面），
 * 不是定时批量抓取——合规语义完全不同。
 *
 * <p>提取策略两级：
 * <ul>
 *   <li>微信公众号文章：正文在 #js_content 容器（服务端渲染，无需 JS 执行）；</li>
 *   <li>通用页面：去除 script/style/nav/header/footer/aside 后取 body 文本，
 *       再交 JdTextCleaner 截尾噪（「相关推荐」「看过的人还看」等）。</li>
 * </ul>
 */
@Component
public class ArticleTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(ArticleTextExtractor.class);

    /** 正文上限：控制 LLM token 消耗与响应时间（完整 JD 一般 < 8000 字） */
    private static final int MAX_CHARS = 15_000;

    private final PageFetcher fetcher;

    public ArticleTextExtractor(PageFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * 抓取并提取文章正文。
     *
     * @return 正文文本；抓取失败或正文为空时返回 null（调用方决定降级提示）
     */
    public String extract(String url) {
        String html;
        try {
            html = fetcher.fetch(url);
        } catch (IOException e) {
            log.info("文章抓取失败: {} — {}", url, e.getMessage());
            return null;
        }
        Document doc = Jsoup.parse(html, url);

        // 微信公众号正文容器
        Element wechat = doc.selectFirst("#js_content");
        if (wechat != null && !wechat.text().isBlank()) {
            return cap(wechat.text());
        }

        // 通用降级：去噪后取 body 文本
        doc.select("script,style,noscript,nav,header,footer,aside,form,iframe").remove();
        Element body = doc.body();
        String text = body == null ? "" : body.text();
        return text.isBlank() ? null : cap(text);
    }

    private static String cap(String text) {
        return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
    }
}
