package com.jobradar.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobradar.core.dto.DashboardDtos.DashboardSummary;
import com.jobradar.core.dto.JobDtos.JobDetail;
import com.jobradar.core.dto.ResumeDtos.ResumeDetail;
import com.jobradar.core.service.DashboardService;
import com.jobradar.core.service.JobService;
import com.jobradar.core.service.ResumeService;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * MCP Server 装配（Spring AI 1.0.0 自动配置收集以下 bean）：
 * <ul>
 *   <li>{@link ToolCallbackProvider}：@Tool 注解方法 → MCP tools；</li>
 *   <li>{@code List<SyncResourceSpecification>}：只读资源（funnel / 默认简历）；</li>
 *   <li>{@code List<SyncPromptSpecification>}：预置提示词模板（prep_interview 等）。</li>
 * </ul>
 * 岗位详情走 get_job_detail tool 而非 URI 模板资源：MCP 资源模板在 0.10.0 SDK 的
 * 自动配置链里支持不完整，tool 参数化更贴合 LLM 调用习惯。
 */
@Configuration
public class McpServerConfig {

    @Bean
    public ToolCallbackProvider jobRadarToolCallbacks(JobRadarMcpTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }

    @Bean
    public List<McpServerFeatures.SyncResourceSpecification> jobRadarResources(
            DashboardService dashboardService, ResumeService resumeService, ObjectMapper objectMapper) {
        return List.of(
                new McpServerFeatures.SyncResourceSpecification(
                        new McpSchema.Resource("jobradar://stats/funnel", "投递漏斗",
                                "实时投递漏斗与本周进展（JSON）", "application/json", null),
                        (exchange, request) -> {
                            try {
                                DashboardSummary s = dashboardService.summary();
                                String json = objectMapper.writeValueAsString(
                                        java.util.Map.of("funnel", s.funnel(), "this_week", s.thisWeek()));
                                return new McpSchema.ReadResourceResult(List.of(
                                        new McpSchema.TextResourceContents(request.uri(), "application/json", json)));
                            } catch (Exception e) {
                                throw new IllegalStateException("读取漏斗失败: " + e.getMessage(), e);
                            }
                        }),
                new McpServerFeatures.SyncResourceSpecification(
                        new McpSchema.Resource("jobradar://resume/default", "默认简历画像",
                                "当前默认简历的结构化画像（JSON）", "application/json", null),
                        (exchange, request) -> {
                            try {
                                ResumeDetail resume = resumeService.list().stream()
                                        .filter(r -> r.isDefault())
                                        .findFirst()
                                        .map(r -> resumeService.detail(r.id()))
                                        .orElse(null);
                                String json = resume == null
                                        ? "{\"detail\":\"尚未上传简历\"}"
                                        : objectMapper.writeValueAsString(resume);
                                return new McpSchema.ReadResourceResult(List.of(
                                        new McpSchema.TextResourceContents(request.uri(), "application/json", json)));
                            } catch (Exception e) {
                                throw new IllegalStateException("读取简历失败: " + e.getMessage(), e);
                            }
                        }));
    }

    @Bean
    public List<McpServerFeatures.SyncPromptSpecification> jobRadarPrompts(
            JobService jobService, ResumeService resumeService, DashboardService dashboardService) {
        return List.of(
                prompt("prep_interview", "面试准备：JD + 简历 → 高频问题清单 + 项目深挖点 + 反问建议",
                        List.of(new McpSchema.PromptArgument("job_id", "岗位 ID（search_jobs 可查）", true)),
                        args -> {
                            long jobId = Long.parseLong(String.valueOf(args.get("job_id")));
                            JobDetail job = jobService.detail(jobId);
                            return """
                                    你是资深技术面试官与求职教练。请基于以下岗位 JD 和我的简历画像，输出：
                                    1. 高频面试问题清单（按 项目深挖 / 专业基础 / 岗位匹配 三类，每类 3-5 题，标注考察点）；
                                    2. 我的简历中最可能被追问的 3 个项目点及应对话术；
                                    3. 我向面试官反问的 3 个高质量问题（体现对该公司业务的理解）。

                                    ## 岗位
                                    公司：%s｜岗位：%s｜城市：%s
                                    JD：
                                    %s

                                    ## 我的简历画像
                                    %s
                                    """.formatted(job.company().name(), job.title(),
                                    job.city() == null ? "未标注" : job.city(),
                                    job.jdText() == null ? "（无 JD 原文）" : job.jdText(),
                                    resumeSnapshot(resumeService));
                        }),
                prompt("weekly_review", "周复盘：本周数据 + 漏斗转化分析 + 下周策略建议", List.of(),
                        args -> {
                            DashboardSummary s = dashboardService.summary();
                            return """
                                    你是我的求职策略教练。以下是我秋招看板的实时数据，请输出：
                                    1. 漏斗转化率分析（哪一层流失最严重，可能原因）；
                                    2. 本周节奏评价（投递量 vs 目标）；
                                    3. 下周三条可执行建议（具体到天）。

                                    ## 漏斗（各阶段数量）
                                    %s

                                    ## 本周
                                    新增投递 %d / 目标 %d；新收录岗位 %d；未读推荐 %d
                                    """.formatted(s.funnel(), s.thisWeek().applied(), s.thisWeek().goal(),
                                    s.thisWeek().newJobs(), s.thisWeek().recommendationsUnread());
                        }),
                prompt("jd_gap_analysis", "差距分析：岗位要求 vs 简历 → 学习补足计划",
                        List.of(new McpSchema.PromptArgument("job_id", "岗位 ID", true)),
                        args -> {
                            long jobId = Long.parseLong(String.valueOf(args.get("job_id")));
                            JobDetail job = jobService.detail(jobId);
                            return """
                                    你是技术成长教练。请对比岗位要求与我的简历画像，输出：
                                    1. 我满足的条件（✓ 清单）；
                                    2. 差距项（按 硬性门槛 / 可短期补足 / 长期积累 分级）；
                                    3. 针对可短期补足项的 2 周学习计划（每天 1-2 小时强度）。

                                    ## 岗位要求
                                    %s

                                    ## JD 原文
                                    %s

                                    ## 我的简历画像
                                    %s
                                    """.formatted(job.requirements() == null ? "（未结构化）" : job.requirements(),
                                    job.jdText() == null ? "（无 JD 原文）" : job.jdText(),
                                    resumeSnapshot(resumeService));
                        }));
    }

    /** 组装一个 SyncPromptSpecification：参数从 GetPromptRequest.arguments() 注入模板 */
    private static McpServerFeatures.SyncPromptSpecification prompt(
            String name, String description, List<McpSchema.PromptArgument> arguments,
            java.util.function.Function<java.util.Map<String, Object>, String> body) {
        return new McpServerFeatures.SyncPromptSpecification(
                new McpSchema.Prompt(name, description, arguments),
                (exchange, request) -> new McpSchema.GetPromptResult(description, List.of(
                        new McpSchema.PromptMessage(McpSchema.Role.USER,
                                new McpSchema.TextContent(body.apply(request.arguments()))))));
    }

    /** 简历快照文本：默认简历的画像 JSON；未上传时给明确提示（prompt 不撒谎） */
    private static String resumeSnapshot(ResumeService resumeService) {
        return resumeService.list().stream()
                .filter(r -> r.isDefault())
                .findFirst()
                .map(r -> {
                    ResumeDetail d = resumeService.detail(r.id());
                    return d.parsed() == null ? "（简历待解析，仅有元信息）" + d.name() : d.parsed().toString();
                })
                .orElse("（尚未上传简历——请提示用户先在 JobRadar 上传简历）");
    }
}
