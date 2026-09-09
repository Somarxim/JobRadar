package com.jobradar.app;

import com.jobradar.core.domain.ApplicationStage;
import com.jobradar.core.dto.ApplicationDtos.ApplicationCreateRequest;
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
        assertThat(aiJob.sourceUrl()).isEqualTo("https://www.nowcoder.com/jobs/443976");
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

        var toPlanned = applicationService.transition(app.id(),
                new StageTransitionRequest(ApplicationStage.PLANNED, "本周投", null, null));
        assertThat(toPlanned.application().stage()).isEqualTo(ApplicationStage.PLANNED);

        var toApplied = applicationService.transition(app.id(),
                new StageTransitionRequest(ApplicationStage.APPLIED, null, "official", null));
        assertThat(toApplied.application().stage()).isEqualTo(ApplicationStage.APPLIED);

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

    private int boardTotal() {
        return applicationService.board().groups().values().stream().mapToInt(java.util.List::size).sum();
    }
}
