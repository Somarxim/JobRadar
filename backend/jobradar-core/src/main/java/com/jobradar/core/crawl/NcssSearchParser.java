package com.jobradar.core.crawl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobradar.core.domain.CrawlSource;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 24365 国家大学生就业服务平台（job.ncss.cn，教育部官方）搜索 API 解析器。
 *
 * <p>探测结论（2026-09-15）：GET /student/jobs/jobslist/ajax/ 匿名可用，
 * 需 Chrome UA + Referer；jobName 关键词搜索、property 单位性质筛选、offset 分页。
 * 央国企/研究所岗位密集，是军工/国企方向的核心增量源。
 *
 * <p>局限：列表接口无 DDL 字段、详情页需登录——jd_text 用列表字段合成
 * （学历/专业/人数/单位性质），DDL 留空，用户点链接自行查看详情。
 */
@Component
public class NcssSearchParser implements SiteParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DETAIL_URL = "https://job.ncss.cn/student/jobs/detail.html?jobId=";

    @Override
    public String name() {
        return "ncss-search";
    }

    @Override
    public List<RawJobPosting> parse(CrawlSource source, String body) {
        JsonNode list;
        try {
            list = MAPPER.readTree(body).path("data").path("list");
        } catch (Exception e) {
            // 接口约定：解析失败抛异常，由管线按源隔离捕获
            throw new IllegalStateException("24365 响应解析失败（可能改版）: " + e.getMessage(), e);
        }
        List<RawJobPosting> out = new ArrayList<>();
        if (!list.isArray()) {
            return out;
        }
        for (JsonNode n : list) {
            String title = text(n, "jobName");
            String company = text(n, "recName");
            if (title == null || company == null) {
                continue;
            }
            String jobId = text(n, "jobId");
            out.add(new RawJobPosting(
                    company,
                    title,
                    text(n, "areaCodeName"),
                    formatSalary(n),
                    formatPublishDate(n.path("publishDate").asLong(0)),
                    null, // 列表接口无 DDL
                    jobId != null ? DETAIL_URL + jobId : source.getUrl(),
                    synthesizeJd(n)));
        }
        return out;
    }

    /** 薪资单位是千元/月，0 = 面议（置空） */
    private static String formatSalary(JsonNode n) {
        double low = n.path("lowMonthPay").asDouble(0);
        double high = n.path("highMonthPay").asDouble(0);
        if (low <= 0 && high <= 0) {
            return null;
        }
        if (high <= 0) {
            return fmt(low) + "K/月起";
        }
        if (low <= 0) {
            return "≤" + fmt(high) + "K/月";
        }
        return fmt(low) + "-" + fmt(high) + "K/月";
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((int) v) : String.valueOf(v);
    }

    private static String formatPublishDate(long epochMs) {
        if (epochMs <= 0) {
            return null;
        }
        return LocalDate.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.of("Asia/Shanghai")).toString();
    }

    /** 列表接口没有 JD 正文（详情页需登录），用结构化字段合成一段可检索的摘要 */
    private static String synthesizeJd(JsonNode n) {
        List<String> parts = new ArrayList<>();
        add(parts, "学历要求", text(n, "degreeName"));
        add(parts, "专业要求", text(n, "major"));
        add(parts, "招聘人数", text(n, "headCount"));
        add(parts, "单位性质", text(n, "recProperty"));
        add(parts, "单位规模", text(n, "recScale"));
        add(parts, "信息来源", text(n, "sourcesNameCh"));
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
