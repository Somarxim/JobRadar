package com.jobradar.mcp;

import com.jobradar.core.dto.JobDtos.JobSummary;
import io.modelcontextprotocol.server.McpServerFeatures;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP Server 注册验证（W4-1）：Spring 上下文在真实 PG（Testcontainers）上起全量装配，
 * 断言 10 个 @Tool 全部解析注册成功（@Tool 的 schema 生成错误会在此时暴露）、
 * resources/prompts 规格齐备，并冒烟调用一个只读 tool 验证 DB 链路。
 *
 * <p>stdio 监听在测试中关闭（不需要真实 MCP 客户端握手；注册正确性由 bean 断言覆盖）。
 * LLM 强制降级（api-key 置空），与 app 模块测试同一约定。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "jobradar.llm.parse.api-key=",
        "jobradar.llm.vision.api-key=",
        "spring.ai.mcp.server.stdio=false",
        // 生产配置里 MCP 进程不做迁移（归 app）；测试要建 schema，覆盖打开
        "spring.flyway.enabled=true",
})
@Testcontainers
class McpToolsRegistrationTest {

    /** 与开发库同镜像族（pgvector/pgvector:pg16）：V1 迁移要装 vector/pg_trgm 扩展 */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired
    private ToolCallbackProvider toolCallbackProvider;
    @Autowired
    private List<McpServerFeatures.SyncResourceSpecification> resources;
    @Autowired
    private List<McpServerFeatures.SyncPromptSpecification> prompts;
    @Autowired
    private JobRadarMcpTools tools;

    @Test
    void registersAllTenTools() {
        var names = Arrays.stream(toolCallbackProvider.getToolCallbacks())
                .map(tc -> tc.getToolDefinition().name())
                .toList();
        assertThat(names).containsExactlyInAnyOrder(
                "search_jobs", "get_job_detail", "add_job", "apply", "update_stage",
                "set_next_action", "today", "weekly_report", "match_job", "recommend_today");
    }

    @Test
    void registersResourcesAndPrompts() {
        assertThat(resources).hasSize(2);
        assertThat(resources.stream().map(r -> r.resource().uri()))
                .containsExactlyInAnyOrder("jobradar://stats/funnel", "jobradar://resume/default");
        assertThat(prompts.stream().map(p -> p.prompt().name()))
                .containsExactlyInAnyOrder("prep_interview", "weekly_review", "jd_gap_analysis");
    }

    @Test
    void searchJobsSmokeOverRealDb() {
        // 空库上不抛异常即可（内容为空列表）——验证 tool → service → 数据库链路连通
        List<JobSummary> out = tools.searchJobs(null, null, null, null, 5);
        assertThat(out).isNotNull();
    }

    @Test
    void updateStageOnMissingApplicationIsFriendlyFailure() {
        // 不猜不写：jobId 无投递记录时返回 ok=false 中文说明，而非抛异常
        var outcome = tools.updateStage(999999L, "interview", null, null);
        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.message()).contains("还没有投递记录");
    }

    @Test
    void updateStageRejectsUnknownStage() {
        var outcome = tools.updateStage(1L, "hired", null, null);
        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.message()).contains("无效阶段");
    }
}
