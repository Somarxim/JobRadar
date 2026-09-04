package com.jobradar.core.domain;

/**
 * 每日推荐反馈状态（recommendations.status）：pending=待处理 / accepted=已采纳 / ignored=已忽略。
 * W3 推荐管线的反馈闭环依赖该字段统计采纳率。
 */
public enum RecommendationStatus {
    PENDING, ACCEPTED, IGNORED;

    @com.fasterxml.jackson.annotation.JsonValue
    public String toJson() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
