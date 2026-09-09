package com.jobradar.core.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;
import java.util.List;

public final class RecommendDtos {

    private RecommendDtos() {
    }

    /** 今日推荐条目（Dashboard 卡片） */
    public record RecommendationView(
            Long id,
            @JsonProperty("job_id") Long jobId,
            String company,
            String title,
            String city,
            @JsonProperty("salary_range") String salaryRange,
            @JsonProperty("source_platform") String sourcePlatform,
            @JsonProperty("source_url") String sourceUrl,
            LocalDate deadline,
            int rank,
            /** 推荐理由：精评时是 LLM 一句话结论；降级时是粗筛命中说明 */
            String reason,
            /** 精评总分（粗筛降级时为规则分），供卡片展示 */
            int score,
            /** true = 经过 LLM 精评；false = 规则粗筛降级产出 */
            @JsonProperty("llm_scored") boolean llmScored,
            String status,
            @JsonProperty("feedback_tag") String feedbackTag) {
    }

    /** 反馈请求：accept=感兴趣（自动建 application 进看板）/ ignore=不感兴趣 */
    public record FeedbackRequest(
            @NotNull @Pattern(regexp = "accept|ignore", message = "action 只能是 accept|ignore")
            String action,
            /** ignore 时可附原因标签（已投过/方向不符/城市不去…），回流评估推荐质量 */
            String tag) {
    }

    /** 管线一轮的运行报告（手动触发接口返回体 / 日志） */
    public record RecommendRunReport(
            int candidates,       // 进入粗筛的候选数
            int coarsePassed,     // 粗筛出线的数（进精评）
            int llmScored,        // 精评成功数（0 = LLM 不可用走降级）
            int recommended,      // 最终推荐条数
            List<String> notes) { // 降级/异常说明
    }
}
