package com.jobradar.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobradar.core.domain.Job;
import com.jobradar.core.domain.MatchReport;
import com.jobradar.core.domain.Resume;
import com.jobradar.core.dto.MatchDtos.MatchReportView;
import com.jobradar.core.exception.NotFoundException;
import com.jobradar.core.exception.UnprocessableException;
import com.jobradar.core.llm.LlmService;
import com.jobradar.core.llm.MatchDetail;
import com.jobradar.core.repository.JobRepository;
import com.jobradar.core.repository.MatchReportRepository;
import com.jobradar.core.repository.ResumeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 匹配 Agent（agent-design.md §4）：简历 × 岗位 → MatchDetail 精评报告。
 *
 * <p>v1 范围说明（与文档的偏差及原因）：文档的 Stage 1 pgvector 粗筛服务于
 * 「每日推荐管线从成百上千岗位里挑 Top 20」的规模场景（W3）；当前单岗位按需匹配
 * 没有粗筛需求，且 embedding 依赖 DashScope 额度（暂不可用）。故 v1 直接 LLM 精评，
 * pgvector 粗筛随 W3 发现引擎一并上线。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>24h 缓存</b>：同 (job, resume) 24 小时内复用最新报告——精评是最贵的
 *       调用（长 prompt），用户反复点「查看」不应重复烧钱。简历/岗位变更后
 *       自然失效（报告绑定当时的 resume_id，编辑简历走 reparse 换新档案后
 *       属同一 resume_id，故缓存按时间窗兜底）。</li>
 *   <li><b>服务端校验兜底</b>：LLM 不保证遵守「hardPass=false → 总分 ≤39」，
 *       落库前强制封顶——永远不信任模型输出的规则遵守度。</li>
 *   <li><b>双路输入</b>：结构化 ResumeProfile JSON 保准确，JD 原文防提取遗漏。</li>
 * </ul>
 */
@Service
public class MatchingService {

    private static final Logger log = LoggerFactory.getLogger(MatchingService.class);

    /** 缓存窗口：同 (job, resume) 24h 内复用（agent-design §4） */
    private static final Duration CACHE_TTL = Duration.ofHours(24);

    private final JobRepository jobRepository;
    private final ResumeRepository resumeRepository;
    private final MatchReportRepository matchReportRepository;
    private final LlmService llmService;
    private final ObjectMapper objectMapper;

    public MatchingService(JobRepository jobRepository, ResumeRepository resumeRepository,
                           MatchReportRepository matchReportRepository,
                           LlmService llmService, ObjectMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.resumeRepository = resumeRepository;
        this.matchReportRepository = matchReportRepository;
        this.llmService = llmService;
        this.objectMapper = objectMapper;
    }

    /**
     * 触发匹配（POST /match/jobs/{job_id}）。同步执行（一次 LLM 调用约 3-10s）。
     * resumeId 为 null 时用默认简历；无默认简历/简历未解析/JD 为空 → 422 引导前置动作。
     *
     * <p>REQUIRES_NEW（W3-3 修正）：推荐管线会循环逐岗精评并 catch 单岗失败继续。
     * 若沿用 REQUIRED 加入外层事务，内层抛出 RuntimeException 会把外层事务标记为
     * rollback-only——即使调用方 catch 住，最终提交也会炸 UnexpectedRollbackException
     * （Spring 事务经典陷阱）。独立事务让单岗失败只回滚自己；已产出的报告作为
     * 24h 缓存独立保留，外层事务成败与之无关。对 Controller 直调场景（原本就是
     * 最外层事务）行为不变。
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public MatchReportView match(long jobId, Long resumeId) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new NotFoundException("岗位不存在: id=" + jobId));
        Resume resume = (resumeId != null ? resumeRepository.findById(resumeId)
                : resumeRepository.findByIsDefaultTrue())
                .orElseThrow(() -> new UnprocessableException(
                        resumeId != null ? "简历不存在: id=" + resumeId
                                : "还没有默认简历，请先在「简历」页上传 PDF"));
        if (resume.getParsed() == null) {
            throw new UnprocessableException(
                    "简历「" + resume.getName() + "」尚未解析成功，请先在简历页点「重新解析」");
        }
        if (job.getJdText() == null || job.getJdText().isBlank()) {
            throw new UnprocessableException("该岗位缺少 JD 文本，无法评估匹配度，请先编辑补充 JD");
        }

        // 24h 缓存：同 (job, resume) 复用最新报告，避免重复调用最贵的 LLM
        Optional<MatchReport> cached = matchReportRepository
                .findFirstByJobIdAndResumeIdOrderByCreatedAtDesc(jobId, resume.getId())
                .filter(r -> r.getCreatedAt().isAfter(Instant.now().minus(CACHE_TTL)));
        if (cached.isPresent()) {
            return toView(cached.get(), true);
        }

        String jobHeader = """
                公司：%s（%s）
                岗位：%s
                城市：%s
                薪资：%s
                """.formatted(
                job.getCompany().getName(),
                job.getCompany().getCompanyType() != null ? job.getCompany().getCompanyType() : "类型未知",
                job.getTitle(),
                job.getCity() != null ? job.getCity() : "未知",
                job.getSalaryRange() != null ? job.getSalaryRange() : "未知");

        MatchDetail raw = llmService.evaluateMatch(jobHeader, job.getJdText(), resume.getParsed())
                .orElseThrow(() -> new UnprocessableException(
                        "AI 匹配评估失败，请稍后重试；也可直接阅读 JD 人工判断"));

        // 服务端规则兜底（不信任模型的规则遵守度）：hardPass=false 总分封顶 39、钳制 0-100，
        // 并用修正后的分数重建 detail——保证 JSONB 与 score_total 列一致，避免前端显示两套分
        int capped = enforceScoreRules(raw);
        MatchDetail detail = capped == raw.scoreTotal() ? raw
                : new MatchDetail(raw.hardChecks(), raw.hardPass(), capped, raw.scoreBreakdown(),
                        raw.matchedSkills(), raw.missingSkills(), raw.highlights(),
                        raw.suggestion(), raw.oneLiner());

        MatchReport report = new MatchReport();
        report.setJob(job);
        report.setResume(resume);
        report.setDetail(writeJson(detail));
        report.setScoreTotal(capped);
        report.setModelUsed(llmService.parseModelName());
        report.setPromptVersion(LlmService.MATCH_PROMPT_VERSION);
        return toView(matchReportRepository.save(report), false);
    }

    /** 最新报告（GET /match/jobs/{job_id}）；无报告 → 404（前端据此展示空态+生成按钮） */
    @Transactional(readOnly = true)
    public MatchReportView latest(long jobId) {
        if (!jobRepository.existsById(jobId)) {
            throw new NotFoundException("岗位不存在: id=" + jobId);
        }
        return matchReportRepository.findByJobIdOrderByCreatedAtDesc(jobId).stream()
                .findFirst()
                .map(r -> toView(r, null))
                .orElseThrow(() -> new NotFoundException("该岗位暂无匹配报告"));
    }

    /** 详情页内嵌最新报告（nullable）：不存在时返回 null 由前端显示空态 */
    @Transactional(readOnly = true)
    public MatchReportView latestOrNull(long jobId) {
        return matchReportRepository.findByJobIdOrderByCreatedAtDesc(jobId).stream()
                .findFirst()
                .map(r -> toView(r, null))
                .orElse(null);
    }

    // ---------- 内部实现 ----------

    /** 服务端规则兜底：hardPass=false 时总分封顶 39；分数钳制在 0-100（表有 CHECK 约束） */
    private static int enforceScoreRules(MatchDetail d) {
        int score = Math.max(0, Math.min(100, d.scoreTotal()));
        if (Boolean.FALSE.equals(d.hardPass())) {
            score = Math.min(score, 39);
        }
        return score;
    }

    private String writeJson(MatchDetail detail) {
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (IOException e) {
            throw new UncheckedIOException("MatchDetail 序列化失败", e);
        }
    }

    private MatchReportView toView(MatchReport r, Boolean cached) {
        MatchDetail detail = null;
        try {
            detail = objectMapper.readValue(r.getDetail(), MatchDetail.class);
        } catch (IOException e) {
            log.warn("匹配报告 {} 的 detail JSON 损坏: {}", r.getId(), e.getMessage());
        }
        return new MatchReportView(r.getId(), r.getJob().getId(), r.getResume().getId(),
                r.getScoreTotal(), detail, r.getModelUsed(), r.getPromptVersion(),
                cached, r.getCreatedAt());
    }
}
