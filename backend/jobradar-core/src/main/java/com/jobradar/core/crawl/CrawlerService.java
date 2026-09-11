package com.jobradar.core.crawl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobradar.core.domain.Company;
import com.jobradar.core.domain.CrawlSource;
import com.jobradar.core.domain.Job;
import com.jobradar.core.repository.CompanyRepository;
import com.jobradar.core.repository.CrawlSourceRepository;
import com.jobradar.core.repository.JobRepository;
import com.jobradar.core.util.CompanyTypeClassifier;
import com.jobradar.core.util.DedupeHash;
import com.jobradar.core.util.JdTextCleaner;
import com.jobradar.core.util.JiebaSearchText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 爬虫管线：抓取 → 解析 → 归一化（公司别名 + 噪音截断）→ 去重 → 落库。
 *
 * <p>关键设计（对应 crawler-engineering 学习笔记）：
 * <ul>
 *   <li><b>单源失败隔离</b>：每个源的 try/catch 互不扩散——国聘改版挂掉不影响牛客。
 *       失败的源记 error 进汇总，last_crawled_at 不更新（下轮继续全量抓该源）。</li>
 *   <li><b>幂等</b>：dedupe_hash 唯一约束 + 应用层先查。爬虫每日全量重跑是常态，
 *       重复条目静默跳过，不报错不更新（岗位内容以首见为准，变化由人工修正）。</li>
 *   <li><b>无大事务</b>：逐条 save（Repository 自带事务），一条脏数据不回滚整批；
 *       爬虫是「尽力而为」语义，不是「全或无」语义。</li>
 * </ul>
 */
@Service
public class CrawlerService {

    private static final Logger log = LoggerFactory.getLogger(CrawlerService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CrawlSourceRepository sourceRepository;
    private final JobRepository jobRepository;
    private final CompanyRepository companyRepository;
    private final PageFetcher fetcher;
    private final ParserRegistry parserRegistry;
    private final CompanyAliases companyAliases;

    public CrawlerService(CrawlSourceRepository sourceRepository, JobRepository jobRepository,
                          CompanyRepository companyRepository, PageFetcher fetcher,
                          ParserRegistry parserRegistry, CompanyAliases companyAliases) {
        this.sourceRepository = sourceRepository;
        this.jobRepository = jobRepository;
        this.companyRepository = companyRepository;
        this.fetcher = fetcher;
        this.parserRegistry = parserRegistry;
        this.companyAliases = companyAliases;
    }

    /** 单源抓取结果 */
    public record SourceResult(String sourceName, int fetched, int created, int duplicated,
                               String error) {
        static SourceResult ok(String name, int fetched, int created, int duplicated) {
            return new SourceResult(name, fetched, created, duplicated, null);
        }

        static SourceResult failed(String name, String error) {
            return new SourceResult(name, 0, 0, 0, error);
        }
    }

    /** 一轮抓取的汇总（REST 返回体 / 日志 / 后续推荐管线的输入信号） */
    public record CrawlRunSummary(Instant startedAt, List<SourceResult> results) {
        public int totalCreated() {
            return results.stream().mapToInt(SourceResult::created).sum();
        }
    }

    /** 跑全部启用的源（每日调度入口） */
    public CrawlRunSummary runAll() {
        return run(sourceRepository.findByEnabledTrue());
    }

    /** 跑单个源（REST 手动触发 / 调试入口） */
    public CrawlRunSummary runOne(long sourceId) {
        return sourceRepository.findById(sourceId)
                .map(s -> run(List.of(s)))
                .orElseThrow(() -> new IllegalArgumentException("爬虫源不存在: id=" + sourceId));
    }

    private CrawlRunSummary run(List<CrawlSource> sources) {
        Instant startedAt = Instant.now();
        List<SourceResult> results = new ArrayList<>();
        for (CrawlSource source : sources) {
            results.add(runSourceSafely(source));
        }
        CrawlRunSummary summary = new CrawlRunSummary(startedAt, results);
        log.info("爬虫一轮完成：{} 个源，新增 {} 条岗位", results.size(), summary.totalCreated());
        return summary;
    }

    /** 失败隔离：单源任何异常（抓取/解析/配置）都收敛为 SourceResult.error */
    private SourceResult runSourceSafely(CrawlSource source) {
        try {
            return runSource(source);
        } catch (Exception e) {
            log.warn("源「{}」抓取失败（已隔离，不影响其他源）: {}", source.getName(), e.getMessage());
            return SourceResult.failed(source.getName(), e.getMessage());
        }
    }

    private SourceResult runSource(CrawlSource source) throws Exception {
        SiteParser parser = parserRegistry.get(source.getParser());
        List<RawJobPosting> postings = fetchAllPages(source, parser);
        String platform = readPlatform(source);

        int created = 0;
        int duplicated = 0;
        for (RawJobPosting p : postings) {
            String company = companyAliases.normalize(p.company());
            String title = p.title() == null ? null : p.title().trim();
            if (company == null || company.isBlank() || title == null || title.isBlank()) {
                continue; // 缺公司/岗位名的条目无法去重也无法展示，丢弃
            }
            String city = p.city() == null || p.city().isBlank() ? null : p.city().trim();
            String hash = DedupeHash.of(company, title, city);
            if (jobRepository.findByDedupeHash(hash).isPresent()) {
                duplicated++;
                continue;
            }
            Job job = new Job();
            job.setCompany(getOrCreateCompany(company));
            job.setTitle(title);
            job.setJdText(JdTextCleaner.truncateNoise(p.jdText() == null ? "" : p.jdText()));
            job.setCity(city);
            job.setSalaryRange(p.salaryRange());
            job.setSourcePlatform(platform);
            job.setSourceUrl(p.url() != null ? p.url() : source.getUrl());
            job.setPublishDate(parseDateLenient(p.publishDate()));
            job.setDeadline(parseDateLenient(p.deadline()));
            job.setDedupeHash(hash);
            job.setSearchText(JiebaSearchText.indexText(
                    job.getCompany().getName(), job.getTitle(), job.getCity(), job.getJdText()));
            jobRepository.save(job);
            created++;
        }

        source.setLastCrawledAt(Instant.now());
        sourceRepository.save(source);
        log.info("源「{}」完成：解析 {} 条，新增 {}，去重跳过 {}", source.getName(), postings.size(), created, duplicated);
        return SourceResult.ok(source.getName(), postings.size(), created, duplicated);
    }

    /**
     * 落库 source_platform 取值：meta.platform 显式配置优先
     * （boss|niuke|guopin|official，见 V2 注释）；缺省 official——官网类源是多数
     */
    private static String readPlatform(CrawlSource source) {
        try {
            JsonNode meta = MAPPER.readTree(source.getMeta() == null ? "{}" : source.getMeta());
            JsonNode n = meta.get("platform");
            return n != null && n.isTextual() && !n.asText().isBlank() ? n.asText() : "official";
        } catch (Exception e) {
            return "official";
        }
    }

    /**
     * 分页抓取：meta.pagination = {"start": 1, "pages": 3}（缺省单页）。
     * 任一页失败即整源失败——宁可少抓一轮也不落半截数据；幂等兜底，下轮重抓无损。
     */
    private List<RawJobPosting> fetchAllPages(CrawlSource source, SiteParser parser) throws Exception {
        int[] range = readPagination(source);
        List<RawJobPosting> all = new ArrayList<>();
        for (int page = range[0]; page < range[0] + range[1]; page++) {
            String body = fetcher.fetch(source, page);
            all.addAll(parser.parse(source, body));
        }
        return all;
    }

    private static int[] readPagination(CrawlSource source) {
        try {
            JsonNode meta = MAPPER.readTree(source.getMeta() == null ? "{}" : source.getMeta());
            JsonNode p = meta.get("pagination");
            if (p == null || !p.isObject()) {
                return new int[]{-1, 1}; // 不分页：page=-1 告知 fetcher 不注入页码
            }
            int start = p.path("start").asInt(1);
            int pages = Math.max(1, Math.min(p.path("pages").asInt(1), 20)); // 上限 20 页，防配置笔误打爆目标站
            return new int[]{start, pages};
        } catch (Exception e) {
            return new int[]{-1, 1};
        }
    }

    private Company getOrCreateCompany(String name) {
        return companyRepository.findByName(name).orElseGet(() -> {
            Company c = new Company();
            c.setName(name);
            // 创建时按公司名规则分类企业性质（运营商/银行/研究所…），避免全落 OTHER
            c.setCompanyType(CompanyTypeClassifier.classify(name));
            return companyRepository.save(c);
        });
    }

    /** 站点日期格式五花八门：ISO 优先，常见中文/斜杠格式容错，失败返回 null（不阻断入库） */
    static LocalDate parseDateLenient(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        for (var fmt : new String[]{"yyyy-MM-dd", "yyyy/M/d", "yyyy.MM.dd", "yyyy年M月d日", "yyyyMMdd"}) {
            try {
                return LocalDate.parse(s, java.time.format.DateTimeFormatter.ofPattern(fmt));
            } catch (Exception ignored) {
                // 尝试下一个格式
            }
        }
        return null;
    }
}
