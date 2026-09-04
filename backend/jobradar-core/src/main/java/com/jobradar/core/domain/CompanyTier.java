package com.jobradar.core.domain;

/**
 * 公司分级（companies.tier）：投递规划用的主观分层，dream=冲刺 / target=主攻 / backup=保底。
 */
public enum CompanyTier {
    DREAM, TARGET, BACKUP, NONE;

    @com.fasterxml.jackson.annotation.JsonValue
    public String toJson() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
