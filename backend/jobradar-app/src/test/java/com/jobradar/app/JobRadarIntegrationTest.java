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
 * <p>LLM 在测试中自动降级：测试环境无 API key → LlmService 模型为 null →
 * 解析类能力走降级路径（正好覆盖「无 LLM 时系统可用」的验收标准）。
 */
@SpringBootTest
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
