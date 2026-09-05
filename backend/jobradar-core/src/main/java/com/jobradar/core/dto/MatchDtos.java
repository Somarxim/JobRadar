package com.jobradar.core.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.jobradar.core.llm.MatchDetail;

import java.time.Instant;

/**
 * 匹配报告 DTO（契约 api-design.md §2.6）。detail 直接透传 MatchDetail——
 * LLM 输出 schema 即契约 schema（与 ResumeDtos 同一原则：一致时不另造层）。
 */
public final class MatchDtos {

    private MatchDtos() {
    }

    /** 触发匹配请求体：resume_id 缺省用默认简历 */
    public record MatchRequest(@JsonProperty("resume_id") Long resumeId) {
    }

    /** 完整匹配报告（POST 触发与 GET 查最新共用） */
    public record MatchReportView(
            Long id,
            @JsonProperty("job_id") Long jobId,
            @JsonProperty("resume_id") Long resumeId,
            @JsonProperty("score_total") int scoreTotal,
            MatchDetail detail,
            @JsonProperty("model_used") String modelUsed,
            @JsonProperty("prompt_version") String promptVersion,
            /** true = 命中 24h 缓存复用（未新调 LLM） */
            Boolean cached,
            @JsonProperty("created_at") Instant createdAt) {
    }
}
