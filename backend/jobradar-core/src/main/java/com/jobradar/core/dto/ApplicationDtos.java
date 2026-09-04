package com.jobradar.core.dto;

import com.jobradar.core.domain.ApplicationStage;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 投递相关 DTO。契约见 docs/api-design.md §2.3。 */
public final class ApplicationDtos {

    private ApplicationDtos() {
    }

    /** 看板卡片（GET /applications 分组列表、POST /applications 响应） */
    public record ApplicationCard(Long id, Long jobId, String companyName, String title,
                                  String city, ApplicationStage stage, int priority,
                                  LocalDate plannedAt, String nextAction, Instant nextActionAt,
                                  Instant updatedAt) {
    }

    /** 投递详情：含岗位摘要与全部标量字段 */
    public record ApplicationDetail(Long id, Long jobId, String companyName, String title,
                                    ApplicationStage stage, String channel, int priority,
                                    LocalDate plannedAt, String nextAction, Instant nextActionAt,
                                    String notes, Instant appliedAt,
                                    Instant createdAt, Instant updatedAt) {
    }

    /** 创建投递（POST /applications）：收藏或加入计划 */
    public record ApplicationCreateRequest(
            @NotNull(message = "job_id 不能为空") Long jobId,
            ApplicationStage stage,
            @Min(1) @Max(5) Integer priority,
            LocalDate plannedAt, String notes) {
    }

    /** 部分更新（PATCH /applications/{id}） */
    public record ApplicationPatchRequest(
            @Min(1) @Max(5) Integer priority,
            LocalDate plannedAt, String nextAction, Instant nextActionAt,
            String notes, String channel) {
    }

    /** 状态流转（POST /applications/{id}/stage）。occurredAt 字符串宽容解析：
     * 带时区按 OffsetDateTime，不带则按服务器本地时区（补录历史投递场景） */
    public record StageTransitionRequest(
            @NotNull(message = "to_stage 不能为空") ApplicationStage toStage,
            String note, String channel, String occurredAt) {
    }

    /** 流转事件时间线项 */
    public record EventItem(Long id, ApplicationStage fromStage, ApplicationStage toStage,
                            String note, Instant createdAt) {
    }

    /** 流转响应：更新后的 application + 最新事件（契约 §2.3） */
    public record TransitionResponse(ApplicationDetail application, List<EventItem> events) {
    }

    /** 看板数据源：按 stage 分组的卡片列表（key 为小写阶段名，与契约一致） */
    public record BoardResponse(Map<String, List<ApplicationCard>> groups) {
    }
}
