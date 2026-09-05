package com.jobradar.core.llm;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 简历结构化解析结果（ResumeProfile，schema 见 docs/agent-design.md §3.1）。
 * 落库时序列化为 JSON 存 resumes.parsed（JSONB）。
 */
public record ParsedResume(
        String name,
        List<Education> education,
        List<String> skills,
        List<Experience> experiences,
        @JsonProperty("target_positions") List<String> targetPositions,
        @JsonProperty("target_cities") List<String> targetCities,
        List<String> awards,
        String summary) {

    /** is985/is211 由 LLM 按公开名单推断——国企/研究所筛选常用的硬条件 */
    public record Education(String school, String degree, String major, String period,
                            @JsonProperty("is985") Boolean is985,
                            @JsonProperty("is211") Boolean is211) {
    }

    /** type: internship|project|competition|research；highlights 为量化成果要点 */
    public record Experience(String type, String org, String role, String period,
                             List<String> highlights) {
    }
}
