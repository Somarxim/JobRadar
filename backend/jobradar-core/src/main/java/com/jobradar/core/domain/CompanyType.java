package com.jobradar.core.domain;

/**
 * 公司类型（companies.company_type）。取值与 V2__init_schema.sql 的 CHECK 约束一一对应，
 * 按目标雇主细化，见 docs/data-model.md v0.2 §company_type 枚举说明。
 *
 * <p>注意：DB 存的是小写串（如 soe_central），Java 常量是大写（SOE_CENTRAL），
 * 桥接由 {@link converter.LowercaseEnumConverter} 完成——不要直接用 @Enumerated(EnumType.STRING)，
 * 它存的是 name() 大写形式，会撞 CHECK 约束。
 */
public enum CompanyType {
    INTERNET,       // 互联网
    SOE_CENTRAL,    // 央企
    SOE_LOCAL,      // 地方国企
    INSTITUTE,      // 军工/科研院所（主战场）
    OPERATOR,       // 运营商
    BANK,           // 银行及软开中心
    FOREIGN,        // 外企
    OTHER
}
