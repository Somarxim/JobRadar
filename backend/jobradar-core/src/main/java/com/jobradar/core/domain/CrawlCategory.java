package com.jobradar.core.domain;

/**
 * 爬虫源分类（crawl_sources.category），对应 docs/target-sources.md 的目标源分组。
 */
public enum CrawlCategory {
    INSTITUTE_MILITARY,  // 军工/研究所招聘平台
    OPERATOR,            // 运营商
    BANK,                // 银行
    SOE_OTHER,           // 其他央国企
    COMMUNITY;           // 社区（牛客校招等）

    @com.fasterxml.jackson.annotation.JsonValue
    public String toJson() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
