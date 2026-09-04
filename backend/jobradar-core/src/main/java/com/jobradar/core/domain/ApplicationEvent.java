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

import java.time.Instant;

/**
 * 投递流转事件（application_events 表）：阶段变更的只追加日志。
 * 事件溯源的极简版——applications.stage 是"当前状态"，本表是"怎么来的"，
 * 支撑 W4 周报 Agent 的复盘统计（如各环节平均停留时长）。
 */
@Entity
@Table(name = "application_events")
@Getter
@Setter
public class ApplicationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false)
    private Application application;

    /** 创建时的首条事件 from_stage 为 NULL（无前置状态） */
    private ApplicationStage fromStage;

    @Column(nullable = false)
    private ApplicationStage toStage;

    private String note;

    // 不用 @CreationTimestamp：补录历史投递时 Service 要显式回写 occurred_at（api-design §2.3），
    // 而 @CreationTimestamp 会在插入时无条件覆盖。默认 now()，Service 按需覆盖。
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
