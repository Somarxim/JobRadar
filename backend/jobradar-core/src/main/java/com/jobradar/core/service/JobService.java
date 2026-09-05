package com.jobradar.core.service;

import com.jobradar.core.domain.Application;
import com.jobradar.core.domain.ApplicationStage;
import com.jobradar.core.domain.Company;
import com.jobradar.core.domain.CompanyTier;
import com.jobradar.core.domain.CompanyType;
import com.jobradar.core.domain.Job;
import com.jobradar.core.dto.JobDtos.CompanyBrief;
import com.jobradar.core.dto.JobDtos.IngestRequest;
import com.jobradar.core.dto.JobDtos.IngestResponse;
import com.jobradar.core.dto.JobDtos.JobCreateRequest;
import com.jobradar.core.dto.JobDtos.JobDetail;
import com.jobradar.core.dto.JobDtos.JobPatchRequest;
import com.jobradar.core.dto.JobDtos.JobSummary;
import com.jobradar.core.dto.PageResponse;
import com.jobradar.core.exception.BadRequestException;
import com.jobradar.core.exception.ConflictException;
import com.jobradar.core.exception.NotFoundException;
import com.jobradar.core.exception.UnprocessableException;
import com.jobradar.core.llm.LlmService;
import com.jobradar.core.repository.ApplicationRepository;
import com.jobradar.core.repository.CompanyRepository;
import com.jobradar.core.repository.JobRepository;
import com.jobradar.core.util.DedupeHash;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 岗位服务：搜索 / 手动录入 / 导入 / 详情 / 修正。
 *
 * <p>@Transactional 的教学点（面试高频）：
 * <ul>
 *   <li>注解加在 Service 方法上而不是 Controller——事务边界 = 业务用例边界，
 *       "创建公司+创建岗位"要么都成功要么都回滚。</li>
 *   <li>readOnly=true 的查询方法会给 Hibernate 提示（设 FlushMode.MANUAL 跳过快照对比），
 *       也让 PG 驱动走只读优化。写方法绝不可标 readOnly。</li>
 *   <li>本类所有对外的 DTO 都在事务内装配——OSIV 已关，出了方法就没有 Session。</li>
 * </ul>
 */
@Service
public class JobService {

    private final JobRepository jobRepository;
    private final CompanyRepository companyRepository;
    private final ApplicationRepository applicationRepository;
    private final LlmService llmService;
    private final MatchingService matchingService;

    // 构造器注入（Spring 4.3+ 单构造器免 @Autowired）：
    // 字段可 final、依赖一目了然、单测 new 出来即可——比字段注入更利于可测试性
    public JobService(JobRepository jobRepository, CompanyRepository companyRepository,
                      ApplicationRepository applicationRepository, LlmService llmService,
                      MatchingService matchingService) {
        this.jobRepository = jobRepository;
        this.companyRepository = companyRepository;
        this.applicationRepository = applicationRepository;
        this.llmService = llmService;
        this.matchingService = matchingService;
    }

    /**
     * 岗位库搜索（GET /jobs）。动态条件用 Specification 拼装而非手写 JPQL 字符串拼接：
     * 类型安全、条件可组合，避免 "where 1=1 and ..." 式字符串注入风险。
     */
    @Transactional(readOnly = true)
    public PageResponse<JobSummary> search(String q, String companyType, String city,
                                           String stage, String tier, java.time.LocalDate deadlineBefore,
                                           String sort, int page, int size) {
        // 字符串参数统一解析为枚举：带了 AttributeConverter 的字段在 Criteria 里要用实体侧类型
        // 比较（converter 自动桥接），非法值尽早 400，不要漏到 SQL 层
        CompanyType typeFilter = parseEnum(CompanyType.class, companyType, "company_type");
        CompanyTier tierFilter = parseEnum(CompanyTier.class, tier, "tier");
        ApplicationStage stageFilter = "none".equals(stage) ? null
                : parseEnum(ApplicationStage.class, stage, "stage");

        Specification<Job> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.isTrue(root.get("active")));

            if (q != null && !q.isBlank()) {
                // W1 用 LIKE 模糊匹配过渡；search_vector 全文检索与语义搜索在 W3 上线
                String like = "%" + q.trim() + "%";
                ps.add(cb.or(
                        cb.like(root.get("title"), like),
                        cb.like(root.get("company").get("name"), like),
                        cb.like(root.get("city"), like)));
            }
            if (typeFilter != null) {
                ps.add(cb.equal(root.get("company").get("companyType"), typeFilter));
            }
            if (tierFilter != null) {
                ps.add(cb.equal(root.get("company").get("tier"), tierFilter));
            }
            if (city != null && !city.isBlank()) {
                ps.add(cb.equal(root.get("city"), city.trim()));
            }
            if (deadlineBefore != null) {
                ps.add(cb.lessThanOrEqualTo(root.get("deadline"), deadlineBefore));
            }
            if (stage != null && !stage.isBlank()) {
                // 按投递阶段筛岗位：用 EXISTS 子查询而非 join——join 会在一对多时产生重复行，
                // 分页 total 就错了。EXISTS/IN 子查询没有这个问题。
                Subquery<Long> sub = query.subquery(Long.class);
                var app = sub.from(Application.class);
                sub.select(app.get("job").get("id"));
                if ("none".equals(stage)) {
                    sub.where(cb.equal(app.get("job").get("id"), root.get("id")));
                    ps.add(cb.not(cb.exists(sub)));
                } else {
                    sub.where(cb.and(
                            cb.equal(app.get("job").get("id"), root.get("id")),
                            cb.equal(app.get("stage"), stageFilter)));
                    ps.add(cb.exists(sub));
                }
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };

        Sort sortSpec = switch (sort == null ? "created_desc" : sort) {
            case "deadline_asc" -> Sort.by(Sort.Direction.ASC, "deadline"); // PG 默认 NULLS LAST
            case "created_desc" -> Sort.by(Sort.Direction.DESC, "createdAt");
            default -> throw new BadRequestException("不支持的 sort: " + sort + "（match_desc 于 W2 上线）");
        };
        // 契约 page 从 1 起；Spring Pageable 从 0 起，此处适配。size 上限 100 防全表拉取。
        Pageable pageable = PageRequest.of(Math.max(page, 1) - 1, Math.min(Math.max(size, 1), 100), sortSpec);
        Page<Job> result = jobRepository.findAll(spec, pageable);

        // 批量装配 application_stage：一次 IN 查询拿回整页岗位的投递记录，避免逐行查询（N+1）
        List<Long> jobIds = result.getContent().stream().map(Job::getId).toList();
        Map<Long, Application> appByJobId = jobIds.isEmpty()
                ? Map.of()
                : applicationRepository.findByJobIdIn(jobIds).stream()
                        .collect(Collectors.toMap(a -> a.getJob().getId(), Function.identity()));

        List<JobSummary> items = result.getContent().stream()
                .map(j -> toSummary(j, appByJobId.get(j.getId())))
                .toList();
        return PageResponse.of(items, result.getTotalElements(), Math.max(page, 1), pageable.getPageSize());
    }

    /** 手动录入（POST /jobs）。company 按名复用或新建，dedupe_hash 命中即 409 */
    @Transactional
    public JobDetail create(JobCreateRequest req) {
        String hash = DedupeHash.of(req.companyName(), req.title(), req.city());
        jobRepository.findByDedupeHash(hash).ifPresent(existing -> {
            throw new ConflictException("岗位已存在（id=" + existing.getId() + "），请勿重复录入");
        });

        Company company = getOrCreateCompany(req.companyName(), req.companyType());
        Job job = new Job();
        job.setCompany(company);
        job.setTitle(req.title().trim());
        job.setJdText(req.jdText() == null ? "" : req.jdText());
        job.setCity(req.city());
        job.setSalaryRange(req.salaryRange());
        job.setSourcePlatform("manual");
        job.setSourceUrl(req.sourceUrl());
        job.setPublishDate(req.publishDate());
        job.setDeadline(req.deadline());
        job.setDedupeHash(hash);
        return toDetail(jobRepository.save(job));
    }

    /**
     * 统一导入（POST /jobs/ingest）W1 文本版。
     * W2 将接入 LLM 对 raw_text/page_html 做结构化；当前要求 hints 提供 company+title，
     * 否则 422 引导走 POST /jobs 人工录入（契约 §3 错误码）。
     */
    @Transactional
    public IngestResponse ingest(IngestRequest req) {
        List<String> warnings = new ArrayList<>();
        var hints = req.hints();

        // 手填 hints 优先；company/title 缺省时由 LLM 从 JD 原文提取（W2）
        String company = hints != null ? trimToNull(hints.company()) : null;
        String title = hints != null ? trimToNull(hints.title()) : null;
        String city = hints != null ? trimToNull(hints.city()) : null;
        String salary = hints != null ? trimToNull(hints.salaryRange()) : null;
        LocalDate deadline = hints != null ? hints.deadline() : null;
        String posterJdText = null;

        if (company == null || title == null) {
            boolean hasText = req.rawText() != null && !req.rawText().isBlank();
            boolean hasImage = req.imageBase64() != null && !req.imageBase64().isBlank();
            if (!hasText && !hasImage) {
                throw new UnprocessableException(
                        "缺少 JD 原文（raw_text）或海报图片（image_base64），无法自动解析，请手动填写公司/岗位");
            }
            // 文本优先于图片（更准更便宜）；图片走多模态海报解析
            var parsed = hasText ? llmService.parseJd(req.rawText())
                    : llmService.parsePoster(req.imageBase64(), req.imageMediaType());
            if (parsed.isEmpty()) {
                throw new UnprocessableException(
                        "AI 解析不可用或失败，请手动填写公司/岗位，或使用 POST /jobs 人工录入");
            }
            var p = parsed.get();
            // 合并原则：用户手填 > AI 提取（人永远是最后裁决者）
            if (company == null) company = trimToNull(p.company());
            if (title == null) title = trimToNull(p.title());
            if (city == null) city = trimToNull(p.city());
            if (salary == null) salary = trimToNull(p.salaryRange());
            if (deadline == null) deadline = parseDateLenient(p.deadline(), "deadline", warnings);
            // 海报路径：模型整理的 jd_text 作为 JD 存档（海报本身没有文字层）
            if (!hasText && p.jdText() != null) {
                posterJdText = p.jdText();
            }
            warnings.add("公司/岗位由 AI 提取，请人工复核");
        }
        if (company == null || title == null) {
            throw new UnprocessableException("AI 未能从 JD 中识别公司/岗位，请手动填写");
        }

        String hash = DedupeHash.of(company, title, city);

        var existing = jobRepository.findByDedupeHash(hash);
        if (existing.isPresent()) {
            // 幂等（契约 §3）：重复导入不报错，返回已存在岗位
            Job j = existing.get();
            return new IngestResponse(j.getId(), true,
                    new IngestResponse.ParsedBrief(j.getCompany().getName(), j.getTitle(), j.getCity(),
                            j.getDeadline()),
                    List.of());
        }

        Company companyEntity = getOrCreateCompany(company, null);
        Job job = new Job();
        job.setCompany(companyEntity);
        job.setTitle(title);
        job.setJdText(req.rawText() != null && !req.rawText().isBlank() ? req.rawText()
                : posterJdText != null ? posterJdText : "");
        job.setCity(city);
        job.setSalaryRange(salary);
        // 插件来源记 extension，粘贴记 manual（source 取值见 api-design §2.2）
        job.setSourcePlatform("extension".equals(req.source()) ? "extension" : "manual");
        job.setSourceUrl(req.url());
        job.setDeadline(deadline);
        job.setDedupeHash(hash);
        Job saved = jobRepository.save(job);

        if (deadline == null) {
            warnings.add("deadline 未能从输入中识别，请人工确认");
        }
        return new IngestResponse(saved.getId(), false,
                new IngestResponse.ParsedBrief(companyEntity.getName(), saved.getTitle(), saved.getCity(),
                        saved.getDeadline()),
                warnings);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** LLM 日期输出容错解析：ISO 优先，失败后尝试常见分隔符，仍不行则 warning 降级 */
    private static LocalDate parseDateLenient(String raw, String field, List<String> warnings) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        for (var fmt : new String[]{"yyyy-MM-dd", "yyyy/M/d", "yyyy.MM.dd", "yyyy年M月d日"}) {
            try {
                return LocalDate.parse(s, java.time.format.DateTimeFormatter.ofPattern(fmt));
            } catch (Exception ignored) {
                // 尝试下一个格式
            }
        }
        warnings.add(field + " 格式未识别: " + s + "，请人工确认");
        return null;
    }

    @Transactional(readOnly = true)
    public JobDetail detail(long id) {
        return toDetail(jobRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("岗位不存在: id=" + id)));
    }

    /** 部分更新（PATCH /jobs/{id}）：null 字段保持原值 */
    @Transactional
    public JobDetail patch(long id, JobPatchRequest req) {
        Job job = jobRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("岗位不存在: id=" + id));
        if (req.title() != null) job.setTitle(req.title());
        if (req.jdText() != null) job.setJdText(req.jdText());
        if (req.jdSummary() != null) job.setJdSummary(req.jdSummary());
        if (req.city() != null) job.setCity(req.city());
        if (req.salaryRange() != null) job.setSalaryRange(req.salaryRange());
        if (req.sourceUrl() != null) job.setSourceUrl(req.sourceUrl());
        if (req.publishDate() != null) job.setPublishDate(req.publishDate());
        if (req.deadline() != null) job.setDeadline(req.deadline());
        if (req.active() != null) job.setActive(req.active());
        // 无需显式 save：事务内托管实体的字段变更会被 Hibernate 脏检查（dirty checking）
        // 自动同步。这也是 @Transactional 边界内"托管对象"语义的一部分。
        return toDetail(job);
    }

    /** 小写字符串 → 枚举（非法值 400）。供 query 参数解析复用 */
    private <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String paramName) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(paramName + " 取值非法: " + raw);
        }
    }

    private Company getOrCreateCompany(String name, CompanyType type) {
        String normalized = name.trim();
        return companyRepository.findByName(normalized)
                .orElseGet(() -> {
                    Company c = new Company();
                    c.setName(normalized);
                    if (type != null) c.setCompanyType(type);
                    return companyRepository.save(c);
                });
    }

    private JobSummary toSummary(Job j, Application app) {
        return new JobSummary(j.getId(), brief(j.getCompany()), j.getTitle(), j.getCity(),
                j.getSalaryRange(), j.getSourcePlatform(), j.getSourceUrl(), j.getDeadline(),
                j.getJdSummary(), null,
                app == null ? null : app.getStage().name().toLowerCase(java.util.Locale.ROOT),
                j.getCreatedAt());
    }

    private JobDetail toDetail(Job j) {
        Application app = applicationRepository.findByJobId(j.getId()).orElse(null);
        return new JobDetail(j.getId(), brief(j.getCompany()), j.getTitle(), j.getJdText(),
                j.getJdSummary(), j.getRequirements(), j.getCity(), j.getSalaryRange(),
                j.getSourcePlatform(), j.getSourceUrl(), j.getPublishDate(), j.getDeadline(),
                j.isActive(),
                app == null ? null : new com.jobradar.core.dto.ApplicationDtos.ApplicationCard(
                        app.getId(), j.getId(), j.getCompany().getName(), j.getTitle(), j.getCity(),
                        app.getStage(), app.getPriority(), app.getPlannedAt(), app.getNextAction(),
                        app.getNextActionAt(), app.getUpdatedAt()),
                matchingService.latestOrNull(j.getId()),
                j.getCreatedAt());
    }

    private CompanyBrief brief(Company c) {
        return new CompanyBrief(c.getId(), c.getName(), c.getCompanyType(), c.getTier());
    }
}
