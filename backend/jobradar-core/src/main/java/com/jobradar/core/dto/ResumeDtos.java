package com.jobradar.core.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.jobradar.core.llm.ParsedResume;

import java.time.Instant;
import java.util.List;

/**
 * 简历相关 DTO（契约见 docs/api-design.md §resumes）。
 * parsed 直接复用 ParsedResume record——LLM 解析的 schema 即对外返回的 schema，
 * 不另造一层 DTO 避免字段漂移（内部模型与契约一致时，透传是最不容易错的选择）。
 */
public final class ResumeDtos {

    private ResumeDtos() {
    }

    /** 列表项：不带完整 parsed，只带摘要信息（列表页不需要全量档案） */
    public record ResumeSummary(
            Long id,
            String name,
            @JsonProperty("is_default") boolean isDefault,
            @JsonProperty("parse_status") String parseStatus,
            String summary,
            @JsonProperty("created_at") Instant createdAt) {
    }

    /** 详情：含完整 ResumeProfile（确认页/匹配页用） */
    public record ResumeDetail(
            Long id,
            String name,
            @JsonProperty("is_default") boolean isDefault,
            @JsonProperty("parse_status") String parseStatus,
            ParsedResume parsed,
            @JsonProperty("created_at") Instant createdAt) {
    }

    /** 列表响应（简历数量少，不分页，直接全量列表） */
    public record ResumeListResponse(List<ResumeSummary> items) {
    }
}
