package com.jobradar.core.domain;

/**
 * 投递生命周期阶段（applications.stage / application_events.from_stage,to_stage）。
 * 流转顺序即看板列序：收藏 → 计划 → 已投递 → 笔试 → 面试 → offer；
 * rejected/withdrawn 为终态（婉拒/主动撤回）。
 */
public enum ApplicationStage {
    COLLECTED, PLANNED, APPLIED, WRITTEN_TEST, INTERVIEW, OFFER, REJECTED, WITHDRAWN;

    @com.fasterxml.jackson.annotation.JsonValue
    public String toJson() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
