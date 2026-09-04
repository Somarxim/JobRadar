package com.jobradar.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 匹配报告（match_reports 表）：简历 × 岗位的 AI 匹配结论（W2 匹配 Agent 产出）。
 * 保留 model_used + prompt_version 是为了可复盘——换模型/改 prompt 后能对比历史报告质量。
 */
@Entity
@Table(name = "match_reports")
@Getter
@Setter
public class MatchReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resume_id", nullable = false)
    private Resume resume;

    /** 总分 0-100（CHECK 约束保证），维度分在 detail 里 */
    @Column(nullable = false)
    private int scoreTotal;

    /** MatchDetail JSON：硬性条件核对 + 维度分 + 缺口 + 建议，schema 见 docs/agent-design.md §3.3 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String detail;

    @Column(nullable = false)
    private String modelUsed;

    @Column(nullable = false)
    private String promptVersion;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
