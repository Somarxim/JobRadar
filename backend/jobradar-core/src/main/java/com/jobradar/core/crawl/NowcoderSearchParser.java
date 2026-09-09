package com.jobradar.core.crawl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobradar.core.domain.CrawlSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 牛客职位搜索 JSON API 解析器（parser = "nowcoder-search"）。
 *
 * <p>W3-2 实地探测结论（2026-09-09）：
 * <ul>
 *   <li>接口：POST https://nowpick.nowcoder.com/u/job/square-search（表单编码，
 *       匿名可用，无需登录态）；参数 query（关键词）/recruitType（1校招 2实习 3社招）/page；</li>
 *   <li>响应：data.datas[] 每项 {data: {...职位字段}}；混有广告位（无 jobName），跳过；</li>
 *   <li>字段：companyName 在 recommendInternCompany 内（组件复用命名的历史遗留）；
 *       JD 正文在 ext（内嵌 JSON 字符串：infos=职位描述/requirements=任职要求/jobStrength=亮点）；</li>
 *   <li>薪资：salaryMin/salaryMax 单位 K、salaryMonth 月数；0-9999999 是「面议」哨兵值；</li>
 *   <li>deliverBegin/deliverEnd 为投递窗口（epoch 毫秒）；窗口超 400 天视为「长期」，不落 deadline
 *       （否则 DDL 倒计时列表被一批 2029 年的假截止日淹没）；</li>
 *   <li>详情页：https://www.nowcoder.com/jobs/{id}（实测 200）。</li>
 * </ul>
 *
 * <p>标题清洗：牛客岗位名常带「【27届校招】」批次前缀与「(J10357)」内部编号后缀，
 * 二者是公司内部标记而非岗位语义，剥离后跨源去重命中率更高。
 */
@Component
public class NowcoderSearchParser implements SiteParser {

    private static final Logger log = LoggerFactory.getLogger(NowcoderSearchParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 详情页地址模板（实测可达） */
    static final String DETAIL_URL = "https://www.nowcoder.com/jobs/";
    /** 投递窗口超过该天数视为「长期有效」，不落 deadline */
    static final long LONG_TERM_DAYS = 400;
    /** 薪资上限哨兵：max 接近 9999999K 即面议 */
    private static final long SALARY_SECRET_MAX = 999_999;

    private static final Pattern LEADING_BATCH_TAG = Pattern.compile("^(【[^】]*】\\s*)+");
    private static final Pattern TRAILING_JOB_CODE = Pattern.compile("\\s*[（(][A-Za-z]?\\d{4,}[A-Za-z0-9]*[）)]\\s*$");

    @Override
    public String name() {
        return "nowcoder-search";
    }

    @Override
    public List<RawJobPosting> parse(CrawlSource source, String body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("源「" + source.getName() + "」响应不是合法 JSON: " + e.getMessage(), e);
        }
        JsonNode datas = root.path("data").path("datas");
        if (!datas.isArray()) {
            // code != 0（限流/封禁）或结构改版——都按失败抛出，由管线隔离并保留下轮重试
            throw new IllegalStateException("源「" + source.getName() + "」响应缺少 data.datas 数组: code="
                    + root.path("code").asInt(-1) + ", msg=" + root.path("msg").asText(""));
        }

        List<RawJobPosting> out = new ArrayList<>();
        for (JsonNode wrapper : datas) {
            JsonNode job = wrapper.path("data");
            RawJobPosting p = toPosting(job);
            if (p != null) {
                out.add(p);
            }
        }
        log.debug("源「{}」解析出 {} 条岗位", source.getName(), out.size());
        return out;
    }

    private static RawJobPosting toPosting(JsonNode job) {
        String title = text(job, "jobName");
        if (title == null) {
            return null; // 广告位/活动卡片，无 jobName
        }
        String company = text(job.path("recommendInternCompany"), "companyName");
        return new RawJobPosting(
                company,
                cleanTitle(title),
                text(job, "jobCity"),
                formatSalary(job),
                epochToDate(job.path("refreshTime").asLong(0)),
                deadlineOf(job),
                DETAIL_URL + job.path("id").asLong(),
                jdTextOf(job));
    }

    /** 剥批次前缀与内部编号后缀（保留语义主体，如「(2027届秋招)」这类非编号修饰不动） */
    static String cleanTitle(String raw) {
        String t = LEADING_BATCH_TAG.matcher(raw).replaceFirst("");
        t = TRAILING_JOB_CODE.matcher(t).replaceFirst("");
        return t.trim();
    }

    /** 薪资：10-20K·14薪；min<=0 或 max 达哨兵值（面议）→ null */
    static String formatSalary(JsonNode job) {
        long min = job.path("salaryMin").asLong(0);
        long max = job.path("salaryMax").asLong(0);
        long month = job.path("salaryMonth").asLong(0);
        if (min <= 0 || max <= 0 || max >= SALARY_SECRET_MAX) {
            return null;
        }
        String base = min + "-" + max + "K";
        return month > 0 ? base + "·" + month + "薪" : base;
    }

    /** deliverEnd（epoch 毫秒）→ 截止日期；窗口过长（长期岗）不落 */
    static String deadlineOf(JsonNode job) {
        long end = job.path("deliverEnd").asLong(0);
        long begin = job.path("deliverBegin").asLong(0);
        if (end <= 0) {
            return null;
        }
        if (begin > 0 && (end - begin) > LONG_TERM_DAYS * 24L * 3600_000L) {
            return null;
        }
        return epochToDate(end);
    }

    /** JD 正文：ext 内嵌 JSON（infos 描述 / requirements 要求 / jobStrength 亮点） */
    static String jdTextOf(JsonNode job) {
        String ext = text(job, "ext");
        if (ext == null) {
            return null;
        }
        try {
            JsonNode e = MAPPER.readTree(ext);
            StringBuilder sb = new StringBuilder();
            appendSection(sb, "职位描述", text(e, "infos"));
            appendSection(sb, "任职要求", text(e, "requirements"));
            appendSection(sb, "岗位亮点", text(e, "jobStrength"));
            return sb.isEmpty() ? null : sb.toString();
        } catch (Exception ex) {
            return ext; // ext 非 JSON 时退化为原文（好过丢弃）
        }
    }

    private static void appendSection(StringBuilder sb, String heading, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append("\n\n");
        }
        sb.append("【").append(heading).append("】\n").append(content.trim());
    }

    private static String epochToDate(long ms) {
        if (ms <= 0) {
            return null;
        }
        return LocalDate.ofInstant(Instant.ofEpochMilli(ms), ZONE).toString();
    }

    private static String text(JsonNode node, String key) {
        JsonNode n = node.get(key);
        return n != null && n.isTextual() && !n.asText().isBlank() ? n.asText() : null;
    }
}
