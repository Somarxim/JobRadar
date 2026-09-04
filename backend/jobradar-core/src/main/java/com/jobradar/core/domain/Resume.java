package com.jobradar.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 简历（resumes 表）：支持多版本简历，is_default 标记默认投递版本。
 * parsed 为 LLM 解析出的 ResumeProfile（W2 填充，schema 见 docs/agent-design.md §3.1）；
 * embedding 列同 Job 一样暂不映射，W2 与 pgvector 一并接入。
 */
@Entity
@Table(name = "resumes")
@Getter
@Setter
public class Resume {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** 原始 PDF 在本机的存储路径（本地单用户应用，不走对象存储） */
    @Column(nullable = false)
    private String filePath;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String parsed;

    @Column(nullable = false)
    private boolean isDefault = false;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
