package com.jobradar.core.crawl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobradar.core.domain.CrawlSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 高校人才网（gaoxiaojob.com）解析器：研究所/事业单位/高校岗位聚合站。
 *
 * <p>探测结论（2026-09-15）：列表是 SSR 内联 JSON——页面 HTML 里直接嵌着
 * {@code window.__JOB_INDEX_EMBED__ = {...}}，正则提取后按 JSON 解析，无额外请求。
 *
 * <p>站点岗位以高校教师/科研岗为主，与本项目目标（军工所/央国企技术岗）只部分重合，
 * 因此支持在源 meta 里配 {@code filterKeywords: ["研究所","航天",...]}——
 * 公司名或岗位名命中任一关键词才保留（不过滤会把大量不相关岗位灌进库）。
 */
@Component
public class GaoxiaojobParser implements SiteParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern EMBED = Pattern.compile(
            "window\\.__JOB_INDEX_EMBED__\\s*=\\s*(\\{.*?\\})\\s*;?\\s*</script>", Pattern.DOTALL);
    private static final String BASE = "https://www.gaoxiaojob.com";

    @Override
    public String name() {
        return "gaoxiaojob";
    }

    @Override
    public List<RawJobPosting> parse(CrawlSource source, String html) {
        Matcher m = EMBED.matcher(html);
        if (!m.find()) {
            return List.of(); // 内联 JSON 缺失 = 站点改版信号，交空让管线记日志
        }
        JsonNode list;
        try {
            list = MAPPER.readTree(m.group(1)).path("list");
        } catch (Exception e) {
            throw new IllegalStateException("高校人才网内联 JSON 解析失败（可能改版）: " + e.getMessage(), e);
        }
        List<String> filters = readFilterKeywords(source);
        List<RawJobPosting> out = new ArrayList<>();
        if (!list.isArray()) {
            return out;
        }
        for (JsonNode n : list) {
            String title = text(n, "jobName");
            String company = text(n, "companyName");
            if (title == null || company == null) {
                continue;
            }
            if (!filters.isEmpty()
                    && filters.stream().noneMatch(k -> company.contains(k) || title.contains(k))) {
                continue;
            }
            out.add(new RawJobPosting(
                    company,
                    title,
                    text(n, "areaName"),
                    null, // 列表无薪资
                    text(n, "releaseTime"),
                    null,
                    absolutize(text(n, "url")),
                    synthesizeJd(n)));
        }
        return out;
    }

    private static List<String> readFilterKeywords(CrawlSource source) {
        try {
            JsonNode meta = MAPPER.readTree(source.getMeta() == null ? "{}" : source.getMeta());
            JsonNode arr = meta.path("filterKeywords");
            if (!arr.isArray()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            arr.forEach(k -> out.add(k.asText()));
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String absolutize(String url) {
        if (url == null) {
            return null;
        }
        return url.startsWith("http") ? url : BASE + (url.startsWith("/") ? url : "/" + url);
    }

    private static String synthesizeJd(JsonNode n) {
        List<String> parts = new ArrayList<>();
        add(parts, "学历要求", text(n, "education"));
        add(parts, "单位性质", text(n, "companyNatureName"));
        add(parts, "单位类型", text(n, "companyTypeName"));
        add(parts, "所属公告", text(n, "announcementName"));
        return String.join("；", parts);
    }

    private static void add(List<String> parts, String label, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(label + "：" + value);
        }
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v != null && v.isTextual() && !v.asText().isBlank() ? v.asText().trim() : null;
    }
}
