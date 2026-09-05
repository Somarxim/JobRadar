package com.jobradar.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * LLM 调用记账（llm_usage 表，V3）。通用映射约定见 {@link Company}。
 *
 * <p>每次调用不论成败都落一行：成功记 token 用量（成本核算），
 * 失败记 error 摘要（排查提示词/模型问题）。成本闸（每日限额）基于此表聚合。
 */
@Entity
@Table(name = "llm_usage")
@Getter
@Setter
public class LlmUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 任务类型：jd_parse | poster_parse | match_assess | ... */
    @Column(nullable = false)
    private String task;

    @Column(nullable = false)
    private String model;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(nullable = false)
    private boolean success;

    @Column(length = 1000)
    private String error;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
