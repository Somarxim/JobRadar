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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 岗位（jobs 表）。
 *
 * <p>本类的三个映射决策（面试可讲）：
 * <ul>
 *   <li><b>故意不映射 embedding / search_vector 两列</b>：ddl-auto=validate 只校验
 *       "实体声明了的列是否在库中存在且类型兼容"，数据库多出的列不报错。
 *       embedding 待 W2 引入 pgvector-java 再映射；search_vector 是 GENERATED ALWAYS
 *       生成列，完全由数据库维护，应用层读写都绕开它。</li>
 *   <li><b>JSONB 先映射为 String</b>（requirements/meta）：{@code @JdbcTypeCode(SqlTypes.JSON)}
 *       是 Hibernate 6 原生 JSON 支持。W1 还没有类型化的 JobRequirements schema
 *       （W2 LLM 结构化输出落地时再换成 POJO 直映），先用 String 透传避免空设计。</li>
 *   <li><b>关联一律 LAZY</b>：JPA 的 *ToOne 默认是 EAGER——查一个 Job 会顺带 JOIN 出
 *       Company，列表查询退化成 N+1。显式 LAZY 后，需要 company 字段时在 Service
 *       事务边界内访问（本项目已关 OSIV，越界访问会立刻抛 LazyInitializationException
 *       暴露问题，而不是悄悄挂连接）。</li>
 * </ul>
 */
@Entity
@Table(name = "jobs")
@Getter
@Setter
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String jdText = "";

    private String jdSummary;

    /** JobRequirements JSON（W2 由 LLM 提取填充），schema 见 docs/agent-design.md §3.2 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String requirements;

    private String city;

    private String salaryRange;

    /** 来源平台（boss/niuke/guopin/official/extension/manual）。不做枚举：爬虫与插件会持续新增来源。 */
    @Column(nullable = false)
    private String sourcePlatform;

    private String sourceUrl;

    /** 去重指纹（company+title+city 归一化后的 hash），录入/爬虫写入前计算，见 V2 表注释 */
    @Column(nullable = false, unique = true)
    private String dedupeHash;

    private LocalDate publishDate;

    private LocalDate deadline;

    /** jieba 分词拼接后的检索文本（W3 应用层写入）；search_vector 由 DB 从本列自动生成 */
    @Column(nullable = false)
    private String searchText = "";

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /** 扩展信息（also_seen_on 多渠道归并记录等） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String meta;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
