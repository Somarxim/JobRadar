package com.jobradar.core.crawl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobradar.core.domain.CrawlSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 智联招聘职位搜索 JSON API 解析器（parser = "zhaopin-search"）。
 *
 * <p>W3 数据源扩展探测结论（2026-09-10）：
 * <ul>
 *   <li>接口：POST https://fe-api.zhaopin.com/c/i/search/positions（JSON body，匿名可用，
 *       需 Referer: https://sou.zhaopin.com/）；参数 kw/cityId/pageSize/pageIndex（0 起）；</li>
 *   <li>响应：data.count 封顶 100（与牛客 totalCount≈200 同类的匿名配额），data.list[]；</li>
 *   <li>字段名不直观：职位名在 name（不是 jobName）、城市在 workCity、学历在 education、
 *       详情页在 positionURL（大写 URL）；salary60 是人类可读薪资（"1-2万"），
 *       salaryReal 是数值区间；property 是企业性质（民营/事业单位/外商独资…）；</li>
 *   <li>无结构化校招过滤：campusTypeSearch/positionSourceType 等参数实测不生效，
 *       结果以社招为主——源默认禁用，启用前需用更精准的关键词（如"2026届校招 Java"）
 *       控制噪音；</li>
 *   <li>详情页 jobs.zhaopin.com/{number}.htm 有安全验证页（Security Verification），
 *       只存链接供用户浏览器点开，不做二次抓取。</li>
 * </ul>
 */
@Component
public class ZhaopinSearchParser implements SiteParser {

    private static final Logger log = LoggerFactory.getLogger(ZhaopinSearchParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "zhaopin-search";
    }

    @Override
    public List<RawJobPosting> parse(CrawlSource source, String body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("源「" + source.getName() + "」响应不是合法 JSON: " + e.getMessage(), e);
        }
        JsonNode list = root.path("data").path("list");
        if (!list.isArray()) {
            // code != 200（限流/改版）——抛出让管线按源隔离，下轮重试
            throw new IllegalStateException("源「" + source.getName() + "」响应缺少 data.list 数组: code="
                    + root.path("code").asInt(-1));
        }

        List<RawJobPosting> out = new ArrayList<>();
        for (JsonNode job : list) {
            RawJobPosting p = toPosting(job);
            if (p != null) {
                out.add(p);
            }
        }
        log.debug("源「{}」解析出 {} 条岗位", source.getName(), out.size());
        return out;
    }

    private static RawJobPosting toPosting(JsonNode job) {
        String title = text(job, "name");
        String company = text(job, "companyName");
        if (title == null || company == null) {
            return null; // 广告/直播卡片（adResponse/liveCard 形态），字段残缺
        }
        String url = text(job, "positionURL");
        if (url == null) {
            url = text(job, "positionUrl"); // 个别条目只有小写变体
        }
        return new RawJobPosting(
                company,
                title,
                text(job, "workCity"),
                salaryOf(job),
                publishDateOf(job),
                null, // 列表接口无截止时间
                url,
                text(job, "jobSummary"));
    }

    /** 薪资：salary60 是人类可读区间（"8000-15000元"/"1-2万"）；空或「面议」→ null */
    static String salaryOf(JsonNode job) {
        String s = text(job, "salary60");
        if (s == null || s.contains("面议")) {
            return null;
        }
        return s;
    }

    /** publishTime "2026-09-08 01:46:26" → "2026-09-08" */
    static String publishDateOf(JsonNode job) {
        String t = text(job, "publishTime");
        return t != null && t.length() >= 10 ? t.substring(0, 10) : null;
    }

    private static String text(JsonNode node, String key) {
        JsonNode n = node.get(key);
        return n != null && n.isTextual() && !n.asText().isBlank() ? n.asText() : null;
    }
}
