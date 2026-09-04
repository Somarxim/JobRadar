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

import java.time.LocalDate;

/**
 * 每日推荐（recommendations 表）：W3 推荐管线产出，(job_id, rec_date) 唯一防同日重复推荐。
 * status + feedback_tag 构成反馈闭环，用于评估推荐质量。
 */
@Entity
@Table(name = "recommendations")
@Getter
@Setter
public class Recommendation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    /** 推荐依据的匹配报告，可空（粗筛阶段可能还没有精评报告） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "match_report_id")
    private MatchReport matchReport;

    @Column(nullable = false)
    private LocalDate recDate;

    /** 当日推荐位次（1 起） */
    @Column(nullable = false)
    private int rank;

    @Column(nullable = false)
    private String reason;

    @Column(nullable = false)
    private RecommendationStatus status = RecommendationStatus.PENDING;

    /** 反馈标签（如"已投过"/"不感兴趣"/"硬性不符"），自由文本 */
    private String feedbackTag;
}
