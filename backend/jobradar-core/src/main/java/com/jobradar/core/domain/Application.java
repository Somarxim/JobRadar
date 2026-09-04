package com.jobradar.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 投递记录（applications 表）：一个岗位最多一条投递（job_id 唯一约束），
 * 生命周期流转见 {@link ApplicationStage}，每次流转追加 {@link ApplicationEvent} 留痕。
 *
 * <p>updated_at 由 @UpdateTimestamp 在每次 UPDATE 时刷新——DB 侧没有触发器，
 * 如果应用忘记写就是脏数据，所以交给 Hibernate 自动维护（这也是不依赖 DB DEFAULT 的原因）。
 */
@Entity
@Table(name = "applications")
@Getter
@Setter
public class Application {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false, unique = true)
    private Job job;

    @Column(nullable = false)
    private ApplicationStage stage = ApplicationStage.COLLECTED;

    /** 投递渠道（official/boss/niuke/email/referral/campus_talk），自由文本不入枚举 */
    private String channel;

    /** 主观优先级 1-5，默认 3 */
    @Column(nullable = false)
    private int priority = 3;

    private LocalDate plannedAt;

    /** 下一步动作描述 + 截止时间，Dashboard 待办列表与日历的数据源 */
    private String nextAction;

    private Instant nextActionAt;

    private String notes;

    /** 实际投递时间（stage 进入 APPLIED 时由 Service 写入） */
    private Instant appliedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;
}
