package com.jobradar.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 配置（settings 表）：KV 存储，含 LLM token 用量累计等运行时配置。
 * value 为 JSONB，简单值也是合法 JSON（如 {"dailyTokenBudget": 50000}）。
 */
@Entity
@Table(name = "settings")
@Getter
@Setter
public class Setting {

    @Id
    private String key;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String value;
}
