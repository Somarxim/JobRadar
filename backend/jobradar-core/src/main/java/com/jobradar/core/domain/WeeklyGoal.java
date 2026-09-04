package com.jobradar.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * 周投递目标（weekly_goals 表）：主键即周一日期，天然幂等。
 * 主键不是自增 id 的表，实体不需要 @GeneratedValue——业务主键直接赋值。
 */
@Entity
@Table(name = "weekly_goals")
@Getter
@Setter
public class WeeklyGoal {

    @Id
    private LocalDate weekStart;

    @Column(nullable = false)
    private int targetCount = 10;

    private String note;
}
