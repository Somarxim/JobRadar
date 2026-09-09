package com.jobradar.core.crawl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobradar.core.domain.CrawlSource;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用列表页解析器（parser = "selector-list"）：不写代码、靠 crawl_sources.meta
 * 里的 CSS 选择器配置适配站点——多数运营商/银行官网是静态列表页，一套配置即可接入。
 *
 * <p>meta JSON 结构：
 * <pre>{@code
 * {
 *   "item":    "tr.job-row",        // 必填：每个岗位条目的选择器
 *   "title":   "a.jt",              // 必填：条目内岗位名选择器
 *   "company": ".co",               // 可选，缺省时由 seed/调用方保证
 *   "city":    ".city",
 *   "salary":  ".sal",
 *   "date":    ".date",             // 发布日期
 *   "url":     "a.jt@href",         // "选择器@属性" 语法取属性值，缺省取文本；
 *                                   // 相对地址按页面 URL 补全
 *   "title_contains": ["工程师"]     // 可选：标题关键词白名单过滤
 * }
 * }</pre>
 *
 * <p>教学点：配置化解析 = 把「站点差异」从代码赶到数据。站点改版时改 meta
 * 即可（甚至可以做成运营配置），不用发版——代价是表达力上限，复杂站点
 * （JS 渲染/翻页 API）仍需专属解析器，二者共存于 ParserRegistry。
 */
@Component
public class SelectorListParser implements SiteParser {

    private static final Logger log = LoggerFactory.getLogger(SelectorListParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "selector-list";
    }

    @Override
    public List<RawJobPosting> parse(CrawlSource source, String html) {
        JsonNode meta = readMeta(source);
        String itemSel = required(meta, "item");
        String titleSel = required(meta, "title");

        // baseUri 供 Jsoup 解析相对链接（absUrl 依赖它）
        Document doc = Jsoup.parse(html, source.getUrl());
        List<RawJobPosting> out = new ArrayList<>();

        List<String> titleFilter = readStringList(meta, "title_contains");
        for (Element item : doc.select(itemSel)) {
            String title = extract(item, titleSel);
            if (title == null || title.isBlank()) {
                continue; // 表头/广告位等假条目
            }
            if (!titleFilter.isEmpty() && titleFilter.stream().noneMatch(title::contains)) {
                continue;
            }
            out.add(new RawJobPosting(
                    extract(item, text(meta, "company")),
                    title.trim(),
                    extract(item, text(meta, "city")),
                    extract(item, text(meta, "salary")),
                    extract(item, text(meta, "date")),
                    null,
                    extract(item, text(meta, "url")),
                    null));
        }
        log.debug("源「{}」解析出 {} 条岗位", source.getName(), out.size());
        return out;
    }

    /** 提取规则："a@href" 取属性并补全为绝对地址；裸选择器取文本 */
    private static String extract(Element item, String rule) {
        if (rule == null || rule.isBlank()) {
            return null;
        }
        String sel = rule;
        String attr = null;
        int at = rule.lastIndexOf('@');
        if (at > 0) {
            sel = rule.substring(0, at);
            attr = rule.substring(at + 1);
        }
        Element el = item.selectFirst(sel);
        if (el == null) {
            return null;
        }
        if (attr == null) {
            String t = el.text().trim();
            return t.isEmpty() ? null : t;
        }
        // absUrl 处理协议相对（//x.com）、路径相对（/a/b）、裸相对（a/b）三种形态
        String v = "href".equals(attr) || "src".equals(attr) ? el.absUrl(attr) : el.attr(attr);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static JsonNode readMeta(CrawlSource source) {
        try {
            return MAPPER.readTree(source.getMeta() == null ? "{}" : source.getMeta());
        } catch (Exception e) {
            throw new IllegalStateException("源「" + source.getName() + "」meta JSON 解析失败: " + e.getMessage(), e);
        }
    }

    private static String required(JsonNode meta, String key) {
        String v = text(meta, key);
        if (v == null) {
            throw new IllegalStateException("selector-list 解析器缺少必填 meta 项: " + key);
        }
        return v;
    }

    private static String text(JsonNode meta, String key) {
        JsonNode n = meta.get(key);
        return n != null && n.isTextual() && !n.asText().isBlank() ? n.asText() : null;
    }

    private static List<String> readStringList(JsonNode meta, String key) {
        JsonNode n = meta.get(key);
        if (n == null || !n.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        n.forEach(x -> { if (x.isTextual()) out.add(x.asText()); });
        return out;
    }
}
