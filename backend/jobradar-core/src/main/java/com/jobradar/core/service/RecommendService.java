package com.jobradar.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobradar.core.domain.Job;
import com.jobradar.core.domain.MatchReport;
import com.jobradar.core.domain.Recommendation;
import com.jobradar.core.domain.RecommendationStatus;
import com.jobradar.core.dto.MatchDtos.MatchReportView;
import com.jobradar.core.dto.RecommendDtos.FeedbackRequest;
import com.jobradar.core.dto.RecommendDtos.RecommendRunReport;
import com.jobradar.core.dto.RecommendDtos.RecommendationView;
import com.jobradar.core.exception.NotFoundException;
import com.jobradar.core.llm.ParsedResume;
import com.jobradar.core.repository.ApplicationRepository;
import com.jobradar.core.repository.JobRepository;
import com.jobradar.core.repository.MatchReportRepository;
import com.jobradar.core.repository.RecommendationRepository;
import com.jobradar.core.repository.ResumeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 每日推荐管线（agent-design §5 的落地版，W3-3）：
 * 候选（近 36h 新岗位）→ 规则粗筛 → LLM 精评 Top N → hardPass 优先取 Top 5 → 落库。
 *
 * <p>与理想设计（embedding 粗筛）的差异：pgvector 粗筛留待数据规模需要时上
 * （当前日增量 ~50-160 条，全量规则打分成本可忽略，embedding 是过早优化）。
 *
 * <p>降级链（每一层失效都还有产出，对应项目「无 LLM 也可用」原则）：
 * <ul>
 *   <li>无默认简历 → 跳过简历相关粗筛项 + 跳过精评，纯方向词粗排；</li>
 *   <li>LLM 不可用/超日预算 → 精评整体放弃，粗筛分直出 Top 5（reason 注明）；</li>
 *   <li>单个岗位精评失败 → 跳过该岗位继续（精评隔离，一岗失败不废整轮）。</li>
 * </ul>
 *
 * <p>幂等：同日重跑先清理当日 PENDING 记录再重算（ACCEPTED/IGNORED 是用户反馈，保留）；
 * (job_id, rec_date) 唯一约束兜底；7 天内推荐过的岗位不重复进候选。
 */
@Service
public class RecommendService {

    private static final Logger log = LoggerFactory.getLogger(RecommendService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 候选窗口：近 36h 新入库（覆盖每日 07:30 爬虫批次 + 跨时区边界） */
    static final Duration CANDIDATE_WINDOW = Duration.ofHours(36);
    /** 兜底候选窗口：36h 无新岗（爬虫断档/周末）时放宽到近 30 天活跃岗位，保推荐不断更 */
    static final Duration FALLBACK_WINDOW = Duration.ofDays(30);
    /** 防重复推荐窗口：近 7 天推过的岗位不再推 */
    static final int NO_REPEAT_DAYS = 7;
    /** 粗筛出线进精评的上限（成本闸：精评 ≤ 20 条/天，见 agent-design §5） */
    static final int MAX_LLM_EVAL = 20;
    /** 最终推荐条数 */
    static final int TOP_N = 5;

    /**
     * 方向词表（docs/target-sources.md §1 的代码化）。
     * 粗筛定位是「别漏」，宁宽勿严——精排交给 LLM。
     */
    static final List<String> DIRECTION_KEYWORDS = List.of(
            "大模型", "Agent", "AIGC", "AI", "智能体", "算法",
            "软件开发", "后端", "Java", "Python", "C++", "Go",
            "信息系统", "信息化", "软件研发", "软件工程", "数据开发", "平台开发");

    private final RecommendationRepository recommendationRepository;
    private final JobRepository jobRepository;
    private final ResumeRepository resumeRepository;
    private final MatchReportRepository matchReportRepository;
    private final ApplicationRepository applicationRepository;
    private final MatchingService matchingService;

    public RecommendService(RecommendationRepository recommendationRepository,
                            JobRepository jobRepository, ResumeRepository resumeRepository,
                            MatchReportRepository matchReportRepository,
                            ApplicationRepository applicationRepository,
                            MatchingService matchingService) {
        this.recommendationRepository = recommendationRepository;
        this.jobRepository = jobRepository;
        this.resumeRepository = resumeRepository;
        this.matchReportRepository = matchReportRepository;
        this.applicationRepository = applicationRepository;
        this.matchingService = matchingService;
    }

    /**
     * 兜底候选池：近 30 天活跃岗位 − 已进看板的 − 近 7 天推荐过的。
     * public 仅为可测试性（集成测试在 app 模块，且共享库里总有别的新建岗位占着
     * 36h 新鲜池，端到端触发不到兜底分支）——业务调用方应只有 {@link #runPipeline}。
     */
    public List<Job> fallbackCandidates(java.util.Set<Long> recentlyRecommended) {
        Instant since = Instant.now().minus(FALLBACK_WINDOW);
        List<Job> pool = jobRepository.findByActiveTrueAndCreatedAtGreaterThanEqual(since);
        List<Long> poolIds = pool.stream().map(Job::getId).toList();
        java.util.Set<Long> applied = poolIds.isEmpty() ? java.util.Set.of()
                : new java.util.HashSet<>(applicationRepository.findByJobIdIn(poolIds)
                        .stream().map(a -> a.getJob().getId()).toList());
        return pool.stream()
                .filter(j -> !applied.contains(j.getId()))
                .filter(j -> !recentlyRecommended.contains(j.getId()))
                .toList();
    }

    /** 粗筛结果：分数 + 命中理由（降级时 reason 直接用它） */
    record CoarseScore(int score, List<String> hits) {
    }

    /** 跑一轮推荐（每日调度 + 手动调试入口共用）。返回运行报告。 */
    @Transactional
    public RecommendRunReport runPipeline() {
        LocalDate today = LocalDate.now();
        List<String> notes = new ArrayList<>();

        // 幂等重跑：清掉今日未处理的推荐，用户的反馈（accept/ignore）保留
        List<Recommendation> todayExisting = recommendationRepository.findByRecDateOrderByRankAsc(today);
        int removed = 0;
        for (Recommendation r : todayExisting) {
            if (r.getStatus() == RecommendationStatus.PENDING) {
                recommendationRepository.delete(r);
                removed++;
            }
        }
        if (removed > 0) {
            notes.add("清理今日待处理推荐 " + removed + " 条（重跑）");
        }

        // 候选池：近 36h 新岗位 − 已进看板的 − 近 7 天推荐过的（两个排除集都批量取回，避免 N+1）
        Instant since = Instant.now().minus(CANDIDATE_WINDOW);
        LocalDate noRepeatSince = today.minusDays(NO_REPEAT_DAYS);
        List<Job> fresh = jobRepository.findByActiveTrueAndCreatedAtGreaterThanEqual(since);
        List<Long> freshIds = fresh.stream().map(Job::getId).toList();
        java.util.Set<Long> hasApplication = freshIds.isEmpty() ? java.util.Set.of()
                : new java.util.HashSet<>(applicationRepository.findByJobIdIn(freshIds)
                        .stream().map(a -> a.getJob().getId()).toList());
        java.util.Set<Long> recentlyRecommended = new java.util.HashSet<>(
                recommendationRepository.findJobIdsByRecDateGreaterThanEqual(noRepeatSince));
        List<Job> candidates = fresh.stream()
                .filter(j -> !hasApplication.contains(j.getId()))
                .filter(j -> !recentlyRecommended.contains(j.getId()))
                .toList();

        // 兜底候选：36h 无新岗（爬虫断档/后端没赶上 07:30 定时窗）时，放宽到近 30 天
        // 活跃岗位。只放宽新鲜度——看板排除与 7 天防重窗口照常生效，防止老岗位反复骚扰。
        if (candidates.isEmpty()) {
            candidates = fallbackCandidates(recentlyRecommended);
            if (!candidates.isEmpty()) {
                notes.add("近 36h 无新入库岗位，兜底取近 30 天活跃且未推荐的 "
                        + candidates.size() + " 个进粗筛");
            }
        }

        ParsedResume resume = defaultResume().orElse(null);
        if (resume == null) {
            notes.add("无默认简历：按纯方向词粗排（跳过精评）");
        }

        // 规则粗筛
        record Scored(Job job, CoarseScore coarse) {
        }
        List<Scored> scored = candidates.stream()
                .map(j -> new Scored(j, coarseScore(j, resume)))
                .filter(s -> s.coarse().score() > 0)
                .sorted(Comparator.comparingInt((Scored s) -> s.coarse().score()).reversed())
                .toList();

        // LLM 精评 Top N（逐岗隔离：一岗失败不废整轮）
        List<Scored> coarseTop = scored.stream().limit(MAX_LLM_EVAL).toList();
        record Evaluated(Job job, CoarseScore coarse, MatchReport report, MatchReportView view) {
        }
        List<Evaluated> evaluated = new ArrayList<>();
        if (resume != null) {
            for (Scored s : coarseTop) {
                try {
                    MatchReportView v = matchingService.match(s.job().getId(), null);
                    MatchReport report = matchReportRepository.findById(v.id()).orElse(null);
                    if (report != null && v.detail() != null) {
                        evaluated.add(new Evaluated(s.job(), s.coarse(), report, v));
                    }
                } catch (Exception e) {
                    log.info("岗位 {} 精评失败（跳过，不影响整轮）: {}", s.job().getId(), e.getMessage());
                }
            }
        }

        // 选取 Top 5：精评成功优先（hardPass 优先 + 总分排序），不足由粗筛补齐
        List<Evaluated> llmRanked = evaluated.stream()
                .filter(e -> e.view().detail().hardPass() == null || e.view().detail().hardPass())
                .sorted(Comparator.comparingInt((Evaluated e) -> e.view().scoreTotal()).reversed())
                .toList();

        List<Recommendation> out = new ArrayList<>();
        for (Evaluated e : llmRanked) {
            if (out.size() >= TOP_N) {
                break;
            }
            String reason = e.view().detail().oneLiner() != null && !e.view().detail().oneLiner().isBlank()
                    ? e.view().detail().oneLiner() : "AI 评分 " + e.view().scoreTotal();
            out.add(build(e.job(), today, out.size() + 1, reason, e.report()));
        }
        if (out.size() < TOP_N) {
            if (resume != null) {
                notes.add(evaluated.isEmpty()
                        ? "LLM 精评不可用：按规则粗筛降级直出"
                        : "精评通过不足 " + TOP_N + " 条，粗筛补齐");
            }
            java.util.Set<Long> picked = new java.util.HashSet<>();
            out.forEach(r -> picked.add(r.getJob().getId()));
            for (Scored s : scored) {
                if (out.size() >= TOP_N) {
                    break;
                }
                if (picked.contains(s.job().getId())) {
                    continue;
                }
                String reason = "方向命中：" + String.join("、", s.coarse().hits())
                        + (resume == null ? "" : "（AI 精评不可用，规则粗排）");
                out.add(build(s.job(), today, out.size() + 1, reason, null));
            }
        }

        recommendationRepository.saveAll(out);
        RecommendRunReport report = new RecommendRunReport(
                candidates.size(), coarseTop.size(), evaluated.size(), out.size(), notes);
        log.info("推荐管线完成：候选 {} → 粗筛出线 {} → 精评 {} → 推荐 {}（{}）",
                report.candidates(), report.coarsePassed(), report.llmScored(), report.recommended(),
                String.join("；", notes));
        return report;
    }

    /**
     * 规则粗筛（无 LLM）：方向词 15/个（封顶 45）+ 目标岗位 20 + 目标城市 15
     * + 薪资透明 5 + 有截止日 5 + 近 3 天发布 10。上限 100。
     */
    static CoarseScore coarseScore(Job job, ParsedResume resume) {
        int score = 0;
        List<String> hits = new ArrayList<>();
        String haystack = ((job.getTitle() == null ? "" : job.getTitle()) + " "
                + (job.getJdText() == null ? "" : job.getJdText())).toLowerCase();

        int directionHits = 0;
        for (String kw : DIRECTION_KEYWORDS) {
            if (haystack.contains(kw.toLowerCase())) {
                directionHits++;
                if (hits.size() < 5) {
                    hits.add(kw);
                }
            }
        }
        score += Math.min(directionHits * 15, 45);

        if (resume != null) {
            String title = job.getTitle() == null ? "" : job.getTitle();
            if (resume.targetPositions() != null && resume.targetPositions().stream()
                    .anyMatch(p -> !p.isBlank() && title.contains(p.trim()))) {
                score += 20;
            }
            String city = job.getCity() == null ? "" : job.getCity();
            if (resume.targetCities() != null && resume.targetCities().stream()
                    .anyMatch(c -> !c.isBlank() && city.contains(c.trim()))) {
                score += 15;
            }
        }
        if (job.getSalaryRange() != null && !job.getSalaryRange().isBlank()) {
            score += 5;
        }
        if (job.getDeadline() != null && !job.getDeadline().isBefore(LocalDate.now())) {
            score += 5;
        }
        if (job.getPublishDate() != null && !job.getPublishDate().isBefore(LocalDate.now().minusDays(3))) {
            score += 10;
        }
        return new CoarseScore(Math.min(score, 100), hits);
    }

    /**
     * 今日推荐列表——只返回 PENDING。已 accept/ignore 的是用户已处理的反馈数据，
     * 留在库里供质量评估但不再展示（用户实测反馈：已处理项混在列表里会干扰
     * 「一炉 5 条、批批替换」的心智模型）。
     */
    @Transactional(readOnly = true)
    public List<RecommendationView> today() {
        return recommendationRepository
                .findByRecDateAndStatusOrderByRankAsc(LocalDate.now(), RecommendationStatus.PENDING)
                .stream().map(RecommendService::toView).toList();
    }

    /**
     * 反馈闭环：accept → 自动建看板卡片（已存在则只改状态，幂等）；ignore → 记标签。
     * 反馈数据用于评估推荐采纳率（PRD 北极星：每周 ≥1 条"不知道的机会"）。
     */
    @Transactional
    public RecommendationView feedback(long id, FeedbackRequest req) {
        Recommendation rec = recommendationRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("推荐不存在: id=" + id));
        if ("accept".equals(req.action())) {
            rec.setStatus(RecommendationStatus.ACCEPTED);
            // 自动进看板；已有投递记录时不重复建（用户可能已手动收藏过）
            if (applicationRepository.findByJobId(rec.getJob().getId()).isEmpty()) {
                applicationRepository.save(newApplication(rec.getJob()));
            }
        } else {
            rec.setStatus(RecommendationStatus.IGNORED);
            rec.setFeedbackTag(req.tag());
        }
        return toView(recommendationRepository.save(rec));
    }

    private static com.jobradar.core.domain.Application newApplication(Job job) {
        var app = new com.jobradar.core.domain.Application();
        app.setJob(job);
        app.setStage(com.jobradar.core.domain.ApplicationStage.COLLECTED);
        return app;
    }

    private static Recommendation build(Job job, LocalDate date, int rank, String reason, MatchReport report) {
        Recommendation r = new Recommendation();
        r.setJob(job);
        r.setRecDate(date);
        r.setRank(rank);
        r.setReason(reason);
        r.setMatchReport(report);
        // 分数不落 recommendations 表（表无该列）：精评分由视图层从 match_report 读出；
        // 粗筛降级时 match_report 为 null，视图 score=0、llm_scored=false，reason 自带命中说明。
        return r;
    }

    private static RecommendationView toView(Recommendation r) {
        Job j = r.getJob();
        MatchReport mr = r.getMatchReport();
        return new RecommendationView(
                r.getId(), j.getId(),
                j.getCompany().getName(), j.getTitle(), j.getCity(),
                j.getSalaryRange(), j.getSourcePlatform(), j.getSourceUrl(), j.getDeadline(),
                r.getRank(), r.getReason(),
                mr != null ? mr.getScoreTotal() : 0,
                mr != null,
                r.getStatus().toJson(), r.getFeedbackTag());
    }

    private Optional<ParsedResume> defaultResume() {
        return resumeRepository.findByIsDefaultTrue()
                .filter(r -> r.getParsed() != null)
                .flatMap(r -> {
                    try {
                        return Optional.of(MAPPER.readValue(r.getParsed(), ParsedResume.class));
                    } catch (Exception e) {
                        log.warn("默认简历 parsed JSON 解析失败，按无简历降级: {}", e.getMessage());
                        return Optional.empty();
                    }
                });
    }
}
