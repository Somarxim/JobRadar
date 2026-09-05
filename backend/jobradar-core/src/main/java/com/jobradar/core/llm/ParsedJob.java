package com.jobradar.core.llm;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * JD 文本结构化解析结果（LLM 输出）。
 * 日期用 String 承接而非 LocalDate：LLM 可能输出 "2026年10月8日" 等格式，
 * 由 LlmService 归一化时容错解析，避免 Jackson 直接反序列化失败。
 */
public record ParsedJob(
        String company,
        String title,
        String city,
        @JsonProperty("salary_range") String salaryRange,
        String deadline,
        @JsonProperty("publish_date") String publishDate) {
}
