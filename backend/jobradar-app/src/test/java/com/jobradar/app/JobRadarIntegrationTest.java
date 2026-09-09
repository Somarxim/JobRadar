package com.jobradar.app;

import com.jobradar.core.domain.ApplicationStage;
import com.jobradar.core.dto.ApplicationDtos.ApplicationCreateRequest;
import com.jobradar.core.dto.ApplicationDtos.ApplicationPatchRequest;
import com.jobradar.core.dto.ApplicationDtos.StageTransitionRequest;
import com.jobradar.core.dto.JobDtos.IngestRequest;
import com.jobradar.core.dto.JobDtos.JobCreateRequest;
import com.jobradar.core.dto.JobDtos.JobPatchRequest;
import com.jobradar.core.service.ApplicationService;
import com.jobradar.core.service.JobService;
import com.jobradar.core.service.ResumeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 集成测试基线（roadmap W2）：Testcontainers 起真实 PostgreSQL（pgvector 镜像——
 * V1 迁移要装 vector/pg_trgm 扩展，普通 postgres 镜像没有），Flyway 全量迁移后
 * 直测 Service 层核心流程。
 *
 * <p>为什么用真实 PG 而非 H2（面试高频）：
 * <ul>
 *   <li>JSONB、pgvector、pg_trgm、ON CONFLICT 都是 PG 方言，H2 模拟不了——
 *       「测试在 H2 上全绿、上 PG 就炸」是经典事故；</li>
 *   <li>Flyway 迁移脚本本身也被测试覆盖（脚本写错容器启动即失败）。</li>
 * </ul>
 *
 * <p>LLM 在测试中强制降级：properties 把 api-key 置空（覆盖开发机环境变量，避免
 * 测试行为依赖本机是否 export 了 key）→ LlmService 模型为 null →
 * 解析类能力走降级路径（正好覆盖「无 LLM 时系统可用」的验收标准）。
 */
@SpringBootTest(properties = {
        "jobradar.llm.parse.api-key=",
        "jobradar.llm.vision.api-key=",
})
@Testcontainers
class JobRadarIntegrationTest {

    /** 与开发库同镜像族（pgvector/pgvector:pg16），保证扩展可用性一致 */
    @Container
    @ServiceConnection  // Boot 3.1+：自动把容器 JDBC 连接注入数据源，无需手写 DynamicPropertySource
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired
    private JobService jobService;
    @Autowired
    private ApplicationService applicationService;
    @Autowired
    private ResumeService resumeService;
    @Autowired
    private com.jobradar.core.repository.CrawlSourceRepository crawlSourceRepository;
    @Autowired
    private com.jobradar.core.crawl.CrawlerService crawlerService;
    @Autowired
    private com.jobradar.core.service.RecommendService recommendService;
    @Autowired
    private com.jobradar.core.repository.JobRepository jobRepository;
    @Autowired
    private com.jobradar.core.repository.RecommendationRepository recommendationRepository;
    @Autowired
    private com.jobradar.core.repository.ApplicationRepository applicationRepository;
    @Autowired
    private com.jobradar.core.service.WeeklyReportService weeklyReportService;
    /** 抓取层打桩：测试不依赖外网（真实站点的连通性/反爬属于运行环境，不属于逻辑正确性） */
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.jobradar.core.crawl.PageFetcher pageFetcher;

    /** 导入幂等：同 company+title+city 二次 ingest 命中 dedupe_hash，不建重复岗位 */
    @Test
    void ingestIsIdempotent() {
        var created = jobService.create(new JobCreateRequest(
                "测试公司甲", null, "Java 工程师", null, "西安",
                null, null, null, null));

        var first = jobService.ingest(new IngestRequest("manual_paste", null, null, null, null, null,
                new IngestRequest.Hints("测试公司甲", "Java 工程师", "西安", null, null)));
        var second = jobService.ingest(new IngestRequest("extension", "https://example.com/j1", null,
                null, null, null,
                new IngestRequest.Hints("测试公司甲", "Java 工程师", "西安", null, null)));

        assertThat(first.alreadyExists()).isTrue();
        assertThat(first.jobId()).isEqualTo(created.id());
        assertThat(second.alreadyExists()).isTrue();
        assertThat(second.jobId()).isEqualTo(created.id());
    }

    /**
     * W2-1 修正验收：hints 提供 company/title 时，AI 只做 enrichment 补全；
     * AI 不可用（测试环境无 key）降级为警告而非 422，岗位照常入库。
     */
    @Test
    void ingestWithHintsSurvivesLlmDown() {
        var res = jobService.ingest(new IngestRequest("manual_paste", null,
                "负责大模型应用平台后端开发，base 北京，截止日期 2026-10-01……", null, null, null,
                new IngestRequest.Hints("测试公司乙", "AI 应用工程师", null, null, null)));

        assertThat(res.alreadyExists()).isFalse();
        assertThat(res.parsed().company()).isEqualTo("测试公司乙");
        assertThat(res.parsed().title()).isEqualTo("AI 应用工程师");
        // AI 不可用 → enrichment 失败降级为警告（而非 W2-1 修正前的"不调 AI 或硬 422"）
        assertThat(res.warnings()).anyMatch(w -> w.contains("AI 补全不可用"));
    }

    /**
     * W3-1/W3-2 爬虫管线：selector-list 静态页 + nowcoder-search 分页 JSON API 双形态 →
     * 别名归一 → 落库；第二轮全量重跑命中 dedupe_hash 幂等跳过；
     * 跨页重复条目去重；坏源（未注册解析器）失败隔离不影响好源。
     */
    @Test
    void crawlPipelineIsIdempotentAndIsolatesFailures() throws Exception {
        // V5 种子源（enabled=true）也会走同一个打桩 fetcher，先清场保证断言只针对本测试的源
        crawlSourceRepository.deleteAll();

        var good = new com.jobradar.core.domain.CrawlSource();
        good.setName("集成测试源");
        good.setUrl("https://example.com/jobs/list");
        good.setParser("selector-list");
        good.setCategory(com.jobradar.core.domain.CrawlCategory.SOE_OTHER);
        good.setMeta("""
                {"item":"tr.job-row","title":"a.jt","company":".co","city":".city",
                 "date":".date","url":"a.jt@href","platform":"guopin"}
                """);
        crawlSourceRepository.save(good);

        // W3-2：JSON API 分页源（牛客形态）——两页数据，跨页有一条重复
        var niuke = new com.jobradar.core.domain.CrawlSource();
        niuke.setName("牛客测试源");
        niuke.setUrl("https://nowpick.nowcoder.com/u/job/square-search");
        niuke.setParser("nowcoder-search");
        niuke.setCategory(com.jobradar.core.domain.CrawlCategory.COMMUNITY);
        niuke.setMeta("""
                {"platform":"niuke",
                 "request":{"method":"POST","contentType":"form","params":{"query":"大模型","recruitType":"1"}},
                 "pagination":{"pageParam":"page","start":1,"pages":2}}
                """);
        crawlSourceRepository.save(niuke);

        var bad = new com.jobradar.core.domain.CrawlSource();
        bad.setName("坏源");
        bad.setUrl("https://example.com/broken");
        bad.setParser("no-such-parser");
        bad.setCategory(com.jobradar.core.domain.CrawlCategory.COMMUNITY);
        crawlSourceRepository.save(bad);

        String htmlFixture = """
                <html><body><table>
                  <tr class="job-row">
                    <td><a class="jt" href="/job/101">Java工程师（2026校招）</a></td>
                    <td class="co">航空工业631所</td><td class="city">西安</td><td class="date">2026-09-01</td>
                  </tr>
                  <tr class="job-row">
                    <td><a class="jt" href="/job/102">软件测试工程师</a></td>
                    <td class="co">爬虫测试公司</td><td class="city">北京</td>
                  </tr>
                </table></body></html>
                """;
        String ncPage1 = """
                {"code":0,"msg":"OK","data":{"datas":[
                  {"data":{"id":443976,"jobName":"【27届校招】AI产品经理（加急）(A235510)",
                    "ext":"{\\"infos\\":\\"负责大模型产品策划\\",\\"requirements\\":\\"本科以上\\"}",
                    "jobCity":"北京","deliverBegin":1786464000000,"deliverEnd":1789084800000,
                    "refreshTime":1788931336000,"salaryMin":20,"salaryMax":40,"salaryMonth":15,
                    "recommendInternCompany":{"companyName":"美团"}}},
                  {"data":{"id":411304,"jobName":"AI软件工程师","ext":"{\\"infos\\":\\"大模型应用开发\\"}",
                    "jobCity":"西安,上海,成都","deliverBegin":1786464000000,"deliverEnd":1881193690000,
                    "refreshTime":1788931336000,"salaryMin":0,"salaryMax":9999999,"salaryMonth":0,
                    "recommendInternCompany":{"companyName":"华为软件技术有限公司"}}}
                ]}}
                """;
        String ncPage2 = """
                {"code":0,"msg":"OK","data":{"datas":[
                  {"data":{"id":443976,"jobName":"【27届校招】AI产品经理（加急）(A235510)",
                    "ext":"{\\"infos\\":\\"负责大模型产品策划\\",\\"requirements\\":\\"本科以上\\"}",
                    "jobCity":"北京","deliverBegin":1786464000000,"deliverEnd":1789084800000,
                    "refreshTime":1788931336000,"salaryMin":20,"salaryMax":40,"salaryMonth":15,
                    "recommendInternCompany":{"companyName":"美团"}}},
                  {"data":{"id":465013,"jobName":"Java 后端","jobCity":"上海",
                    "deliverBegin":1786464000000,"deliverEnd":1789084800000,
                    "refreshTime":1788931336000,"salaryMin":16,"salaryMax":22,"salaryMonth":18,
                    "recommendInternCompany":{"companyName":"兴证全球基金"}}}
                ]}}
                """;
        org.mockito.Mockito.when(pageFetcher.fetch(
                        org.mockito.ArgumentMatchers.any(com.jobradar.core.domain.CrawlSource.class),
                        org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(inv -> {
                    com.jobradar.core.domain.CrawlSource s = inv.getArgument(0);
                    int page = inv.getArgument(1);
                    if ("nowcoder-search".equals(s.getParser())) {
                        return page == 1 ? ncPage1 : ncPage2;
                    }
                    return htmlFixture;
                });

        var first = crawlerService.runAll();
        var goodFirst = first.results().stream()
                .filter(r -> r.sourceName().equals("集成测试源")).findFirst().orElseThrow();
        assertThat(goodFirst.error()).isNull();
        assertThat(goodFirst.created()).isEqualTo(2);
        // 坏源失败被隔离：error 落进结果，不阻断整轮
        var badResult = first.results().stream()
                .filter(r -> r.sourceName().equals("坏源")).findFirst().orElseThrow();
        assertThat(badResult.error()).contains("未注册的解析器");

        // JSON API 源：两页 4 条 → 3 新增 + 1 跨页去重
        var niukeFirst = first.results().stream()
                .filter(r -> r.sourceName().equals("牛客测试源")).findFirst().orElseThrow();
        assertThat(niukeFirst.error()).isNull();
        assertThat(niukeFirst.fetched()).isEqualTo(4);
        assertThat(niukeFirst.created()).isEqualTo(3);
        assertThat(niukeFirst.duplicated()).isEqualTo(1);

        // 公司别名归一：航空工业631所 → 中国航空工业计算技术研究所
        var job = jobService.search("Java工程师", null, null, null, null, null, "created_desc", 1, 10)
                .items().stream()
                .filter(j -> j.title().contains("Java工程师")).findFirst().orElseThrow();
        assertThat(job.company().name()).isEqualTo("中国航空工业计算技术研究所");
        assertThat(job.sourcePlatform()).isEqualTo("guopin");

        // 牛客条目字段映射：标题清洗/薪资/截止日/详情链接
        var aiJob = jobService.search("AI产品经理", null, null, null, null, null, "created_desc", 1, 10)
                .items().stream()
                .filter(j -> j.title().contains("AI产品经理")).findFirst().orElseThrow();
        assertThat(aiJob.title()).isEqualTo("AI产品经理（加急）"); // 【批次】与 (A235510) 编号已剥离
        assertThat(aiJob.company().name()).isEqualTo("美团");
        assertThat(aiJob.salaryRange()).isEqualTo("20-40K·15薪");
        assertThat(aiJob.deadline()).isNotNull();
        assertThat(aiJob.sourceUrl()).isEqualTo("https://www.nowcoder.com/jobs/detail/443976");
        assertThat(aiJob.sourcePlatform()).isEqualTo("niuke");

        // 面议 + 长期投递窗口 → salary/deadline 均不落库
        var hwJob = jobService.search("AI软件工程师", null, null, null, null, null, "created_desc", 1, 10)
                .items().stream()
                .filter(j -> j.title().equals("AI软件工程师")).findFirst().orElseThrow();
        assertThat(hwJob.salaryRange()).isNull();
        assertThat(hwJob.deadline()).isNull();

        // 幂等：全量重跑，0 新增；last_crawled_at 已更新
        var second = crawlerService.runAll();
        var goodSecond = second.results().stream()
                .filter(r -> r.sourceName().equals("集成测试源")).findFirst().orElseThrow();
        assertThat(goodSecond.created()).isZero();
        assertThat(goodSecond.duplicated()).isEqualTo(2);
        var niukeSecond = second.results().stream()
                .filter(r -> r.sourceName().equals("牛客测试源")).findFirst().orElseThrow();
        assertThat(niukeSecond.created()).isZero();
        assertThat(niukeSecond.duplicated()).isEqualTo(4); // 4 条全量重抓全部命中去重
        assertThat(crawlSourceRepository.findById(good.getId()).orElseThrow().getLastCrawledAt()).isNotNull();
        assertThat(crawlSourceRepository.findById(niuke.getId()).orElseThrow().getLastCrawledAt()).isNotNull();
    }

    /** 核心流转：收藏 → 计划 → 投递（channel 必填）→ 事件留痕；同阶段重复流转幂等 */
    @Test
    void applicationTransitionWritesEvents() {
        var job = jobService.create(new JobCreateRequest(
                "测试公司乙", null, "后端开发", null, "北京", null, null, null, null));
        var app = applicationService.create(new ApplicationCreateRequest(job.id(), null, null, null, null));
        assertThat(app.stage()).isEqualTo(ApplicationStage.COLLECTED);
        // 自动待办：新建即按阶段生成，无时间（Dashboard 显示"尽快"）
        assertThat(app.nextAction()).isEqualTo("评估是否投递");
        assertThat(app.nextActionAt()).isNull();

        var toPlanned = applicationService.transition(app.id(),
                new StageTransitionRequest(ApplicationStage.PLANNED, "本周投", null, null));
        assertThat(toPlanned.application().stage()).isEqualTo(ApplicationStage.PLANNED);
        // 自动待办随阶段替换
        assertThat(toPlanned.application().nextAction()).isEqualTo("完成投递");

        var toApplied = applicationService.transition(app.id(),
                new StageTransitionRequest(ApplicationStage.APPLIED, null, "official", null));
        assertThat(toApplied.application().stage()).isEqualTo(ApplicationStage.APPLIED);
        assertThat(toApplied.application().nextAction()).isEqualTo("跟进进度，准备笔试");

        // 幂等：重复流转到 applied 不追加事件
        var again = applicationService.transition(app.id(),
                new StageTransitionRequest(ApplicationStage.APPLIED, null, "official", null));
        assertThat(again.events()).hasSize(toApplied.events().size());

        // 事件链：create（collected）+ planned + applied = 3 条；倒序返回（最新在前），
        // 创建事件在末尾，其 fromStage 为 NULL（"从无到有"语义）
        assertThat(toApplied.events()).hasSize(3);
        assertThat(toApplied.events().get(2).fromStage()).isNull();
        assertThat(toApplied.events().get(0).toStage()).isEqualTo(ApplicationStage.APPLIED);
    }

    /**
     * 周报（W4-2）：本周事件出现在流水与统计中；narrative=false 纯数据版不依赖 LLM；
     * 未来周偏移被拒。共享库下有其他测试的事件，断言用「包含」而非「等于」。
     */
    @Test
    void weeklyReportReflectsThisWeekEvents() {
        var job = jobService.create(new JobCreateRequest(
                "测试公司周报甲", null, "嵌入式开发", null, "西安", null, null, null, null));
        var app = applicationService.create(new ApplicationCreateRequest(job.id(), null, null, null, null));
        applicationService.transition(app.id(),
                new StageTransitionRequest(ApplicationStage.APPLIED, null, "official", null));

        var report = weeklyReportService.report(0, false);
        assertThat(report.llmGenerated()).isFalse();
        assertThat(report.weekStart().getDayOfWeek()).isEqualTo(java.time.DayOfWeek.MONDAY);
        // 本周至少有一条 applied 流入（本条），且事件流水里能找回这家公司
        assertThat(report.stats().applied()).isGreaterThanOrEqualTo(1);
        assertThat(report.events())
                .anySatisfy(e -> {
                    assertThat(e.company()).isEqualTo("测试公司周报甲");
                    assertThat(e.toStage()).isEqualTo("applied");
                });

        assertThatThrownBy(() -> weeklyReportService.report(1, false))
                .isInstanceOf(com.jobradar.core.exception.BadRequestException.class);
    }

    /**
     * 待办自动生成生命周期：流转到笔试自动换文案、终态清空自动待办、
     * 用户自定义文案流转时不被覆盖、待办查询排除终态且无时间的排最前。
     */
    @Test
    void autoNextActionLifecycle() {
        var job = jobService.create(new JobCreateRequest(
                "测试公司待办甲", null, "算法工程师", null, "北京", null, null, null, null));
        var auto = applicationService.create(new ApplicationCreateRequest(job.id(), null, null, null, null));

        var toTest = applicationService.transition(auto.id(),
                new StageTransitionRequest(ApplicationStage.WRITTEN_TEST, null, null, null));
        assertThat(toTest.application().nextAction()).isEqualTo("参加笔试");

        var rejected = applicationService.transition(auto.id(),
                new StageTransitionRequest(ApplicationStage.REJECTED, null, null, null));
        assertThat(rejected.application().nextAction()).isNull();
        assertThat(rejected.application().nextActionAt()).isNull();

        // 自定义待办（用户 PATCH 过文案）：流转不覆盖
        var job2 = jobService.create(new JobCreateRequest(
                "测试公司待办乙", null, "测开", null, "上海", null, null, null, null));
        var custom = applicationService.create(new ApplicationCreateRequest(job2.id(), null, null, null, null));
        applicationService.patch(custom.id(), new ApplicationPatchRequest(
                null, null, "问师兄要内推码", null, null, null));
        var applied = applicationService.transition(custom.id(),
                new StageTransitionRequest(ApplicationStage.APPLIED, null, "official", null));
        assertThat(applied.application().nextAction()).isEqualTo("问师兄要内推码");

        // 待办查询：带时间的排在无时间自动待办之后；终态条目不出现
        var job3 = jobService.create(new JobCreateRequest(
                "测试公司待办丙", null, "Java 开发", null, "杭州", null, null, null, null));
        var timed = applicationService.create(new ApplicationCreateRequest(job3.id(), null, null, null, null));
        applicationService.patch(timed.id(), new ApplicationPatchRequest(
                null, null, null, java.time.Instant.now().plusSeconds(86400), null, null));
        applicationService.transition(custom.id(),
                new StageTransitionRequest(ApplicationStage.REJECTED, null, null, null));

        var todos = applicationRepository.findNextActions();
        var ids = todos.stream().map(com.jobradar.core.domain.Application::getId).toList();
        assertThat(ids).contains(timed.id());
        assertThat(ids).doesNotContain(custom.id());
        // timed 之前的条目必须都是无时间的（nulls-first 排序成立，且不依赖其他测试的数据）
        int timedIdx = ids.indexOf(timed.id());
        assertThat(todos.stream().limit(timedIdx).allMatch(a -> a.getNextActionAt() == null)).isTrue();
    }

    /** 归档过滤：岗位归档后从看板消失（W1 末用户反馈修复的回归防护） */
    @Test
    void archivedJobExcludedFromBoard() {
        var job = jobService.create(new JobCreateRequest(
                "测试公司丙", null, "测试工程师", null, "上海", null, null, null, null));
        applicationService.create(new ApplicationCreateRequest(job.id(), null, null, null, null));
        assertThat(boardTotal()).isGreaterThanOrEqualTo(1);

        jobService.patch(job.id(), new JobPatchRequest(
                null, null, null, null, null, null, null, null, false));

        boolean onBoard = applicationService.board().groups().values().stream()
                .flatMap(java.util.List::stream)
                .anyMatch(c -> c.jobId() == job.id());
        assertThat(onBoard).isFalse();
    }

    /** 简历降级路径：无 LLM 可用时上传仍为 pending（原件落盘，可事后 reparse） */
    @Test
    void resumeUploadWithoutLlmIsPending() {
        // 伪 PDF（有魔数无结构）：PDFBox 解析失败 → 文本为 null → pending
        // （测试环境即便配了 LLM key 也不会被调用——解析在抽文本阶段就失败了）
        byte[] fakePdf = "%PDF-1.4\n(fake body for degradation test)".getBytes();
        var detail = resumeService.upload("fake.pdf", fakePdf);

        assertThat(detail.parseStatus()).isEqualTo("pending");
        assertThat(detail.parsed()).isNull();
        // 首份自动默认
        assertThat(detail.isDefault()).isTrue();
    }

    /**
     * W3-3 推荐管线：候选过滤（无关键词/已进看板/近 7 天推过 三类排除）→
     * 无简历时纯规则粗排降级 → 重跑幂等（PENDING 重算、反馈保留）→
     * feedback accept 自动建看板卡片、ignore 记标签。
     */
    @Test
    void recommendPipelineDegradesIdempotentAndClosesLoop() {
        // 命中方向词 ×2（应被推荐）。共享库里有其他测试遗留岗位竞争 Top 5，
        // 故把这两条的分顶满：3 个方向词（45 封顶）+ 薪资/截止日/近日发布（+20）= 65 稳进。
        var hitA = jobService.create(new JobCreateRequest("推荐测试甲", null, "Java 后端工程师（大模型方向）",
                "负责大模型推理平台的 Java 后端开发", "西安", "15-25K", null,
                java.time.LocalDate.now(), java.time.LocalDate.now().plusDays(30)));
        var hitB = jobService.create(new JobCreateRequest("推荐测试乙", null, "大模型算法工程师",
                "参与大模型训练与推理优化，使用 Python", "北京", "30-50K", null,
                java.time.LocalDate.now(), java.time.LocalDate.now().plusDays(30)));
        // 无方向词（粗筛 0 分，不推荐）
        var miss = jobService.create(new JobCreateRequest("推荐测试丙", null, "行政前台",
                "负责前台接待与访客登记", "西安", null, null, null, null));
        // 命中方向词但已进看板（不重复推荐）
        var boarded = jobService.create(new JobCreateRequest("推荐测试丁", null, "Java 开发工程师",
                "Java 业务开发", null, null, null, null, null));
        applicationService.create(new ApplicationCreateRequest(boarded.id(), null, null, null, null));
        // 命中方向词但近 7 天内推荐过（防重复窗口）
        var pushed = jobService.create(new JobCreateRequest("推荐测试戊", null, "Python 后端工程师",
                "Python 后端开发", null, null, null, null, null));
        var past = new com.jobradar.core.domain.Recommendation();
        past.setJob(jobRepository.findById(pushed.id()).orElseThrow());
        past.setRecDate(java.time.LocalDate.now().minusDays(3));
        past.setRank(1);
        past.setReason("历史推荐");
        recommendationRepository.save(past);

        // 无默认简历 → 纯规则粗排降级，LLM 不参与
        var report = recommendService.runPipeline();
        assertThat(report.llmScored()).isZero();
        assertThat(report.notes()).anyMatch(n -> n.contains("无默认简历"));

        var recs = recommendService.today();
        var byJob = recs.stream().collect(java.util.stream.Collectors.toMap(
                com.jobradar.core.dto.RecommendDtos.RecommendationView::jobId, r -> r, (x, y) -> x));
        assertThat(byJob).containsKeys(hitA.id(), hitB.id());
        assertThat(byJob).doesNotContainKeys(miss.id(), boarded.id(), pushed.id());
        assertThat(recs).allMatch(r -> !r.llmScored());
        // 位次连续、理由可读
        assertThat(recs.stream().map(com.jobradar.core.dto.RecommendDtos.RecommendationView::rank).sorted().toList())
                .isEqualTo(java.util.stream.IntStream.rangeClosed(1, recs.size()).boxed().toList());
        assertThat(byJob.get(hitA.id()).reason()).contains("方向命中");

        // 重跑幂等：PENDING 全部重算，同日同岗不重复（(job_id, rec_date) 唯一约束兜底）
        int firstCount = recommendService.today().size();
        recommendService.runPipeline();
        var rerun = recommendService.today();
        assertThat(rerun).hasSize(firstCount);
        assertThat(rerun.stream().map(com.jobradar.core.dto.RecommendDtos.RecommendationView::jobId).distinct().count())
                .isEqualTo(rerun.size());

        // 反馈闭环：accept → 自动建看板卡片；ignore → 记标签；处理后从今日列表消失（只展示待处理一炉）
        var accRec = rerun.stream().filter(r -> r.jobId().equals(hitA.id())).findFirst().orElseThrow();
        var afterAccept = recommendService.feedback(accRec.id(),
                new com.jobradar.core.dto.RecommendDtos.FeedbackRequest("accept", null));
        assertThat(afterAccept.status()).isEqualTo("accepted");
        assertThat(applicationRepository.findByJobId(hitA.id())).isPresent();

        var ignRec = rerun.stream().filter(r -> r.jobId().equals(hitB.id())).findFirst().orElseThrow();
        var afterIgnore = recommendService.feedback(ignRec.id(),
                new com.jobradar.core.dto.RecommendDtos.FeedbackRequest("ignore", "方向不符"));
        assertThat(afterIgnore.status()).isEqualTo("ignored");
        assertThat(afterIgnore.feedbackTag()).isEqualTo("方向不符");

        var pendingOnly = recommendService.today();
        assertThat(pendingOnly.stream().map(com.jobradar.core.dto.RecommendDtos.RecommendationView::jobId))
                .doesNotContain(hitA.id(), hitB.id());
        assertThat(pendingOnly).hasSize(rerun.size() - 2);

        // 已反馈的记录是用户资产：重跑保留在库里（供推荐质量评估）但不进展示列表；
        // 已 accept 的岗位进 7 天窗口不再推
        recommendService.runPipeline();
        var allToday = recommendationRepository.findByRecDateOrderByRankAsc(java.time.LocalDate.now());
        var byJobAfter = allToday.stream().collect(java.util.stream.Collectors.toMap(
                r -> r.getJob().getId(), r -> r, (x, y) -> x));
        assertThat(byJobAfter.get(hitA.id()).getStatus())
                .isEqualTo(com.jobradar.core.domain.RecommendationStatus.ACCEPTED);
        assertThat(byJobAfter.get(hitB.id()).getStatus())
                .isEqualTo(com.jobradar.core.domain.RecommendationStatus.IGNORED);
        assertThat(recommendService.today().stream()
                .map(com.jobradar.core.dto.RecommendDtos.RecommendationView::jobId))
                .doesNotContain(hitA.id(), hitB.id());
        // accept 幂等：重跑 + 再 accept 不产生重复看板卡片
        recommendService.feedback(byJobAfter.get(hitA.id()).getId(),
                new com.jobradar.core.dto.RecommendDtos.FeedbackRequest("accept", null));
        assertThat(applicationRepository.findByJobId(hitA.id())).isPresent();
    }

    /**
     * 批量删除（W3 UX）：无关联数据的岗位物理删除；有投递/推荐记录的归档保留
     * （岗位从列表消失但历史数据不丢）；不存在的 id 计入 missing 不报错。
     */
    @Test
    void batchDeletePurgesOrArchivesByReference() {
        var clean = jobService.create(new JobCreateRequest(
                "批删测试甲", null, "临时岗位A", null, null, null, null, null, null));
        var withApp = jobService.create(new JobCreateRequest(
                "批删测试乙", null, "临时岗位B", null, null, null, null, null, null));
        applicationService.create(new ApplicationCreateRequest(withApp.id(), null, null, null, null));
        var withRec = jobService.create(new JobCreateRequest(
                "批删测试丙", null, "临时岗位C", null, null, null, null, null, null));
        var rec = new com.jobradar.core.domain.Recommendation();
        rec.setJob(jobRepository.findById(withRec.id()).orElseThrow());
        rec.setRecDate(java.time.LocalDate.now());
        rec.setRank(9);
        rec.setReason("测试推荐记录");
        recommendationRepository.save(rec);

        var res = jobService.batchDelete(java.util.List.of(
                clean.id(), withApp.id(), withRec.id(), 99999999L));
        assertThat(res.deleted()).isEqualTo(1);
        assertThat(res.archived()).isEqualTo(2);
        assertThat(res.missing()).isEqualTo(1);

        // 无关联 → 真删；有关联 → 归档且引用数据完好
        assertThat(jobRepository.findById(clean.id())).isEmpty();
        assertThat(jobRepository.findById(withApp.id()).orElseThrow().isActive()).isFalse();
        assertThat(jobRepository.findById(withRec.id()).orElseThrow().isActive()).isFalse();
        assertThat(applicationRepository.findByJobId(withApp.id())).isPresent();
    }

    private int boardTotal() {
        return applicationService.board().groups().values().stream().mapToInt(java.util.List::size).sum();
    }
}
