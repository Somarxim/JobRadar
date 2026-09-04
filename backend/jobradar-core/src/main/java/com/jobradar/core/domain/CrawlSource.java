package com.jobradar.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 爬虫源配置（crawl_sources 表）：W3 爬虫管线的数据源注册表。
 * parser 为解析器标识（站点适配插件化，见 roadmap 风险登记"单源失败隔离"）。
 */
@Entity
@Table(name = "crawl_sources")
@Getter
@Setter
public class CrawlSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private String url;

    @Column(nullable = false)
    private String parser;

    @Column(nullable = false)
    private CrawlCategory category;

    @Column(nullable = false)
    private boolean enabled = true;

    private Instant lastCrawledAt;

    /** 站点特定配置（分页规则/请求头/栏目映射等） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String meta;
}
