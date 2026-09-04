package com.jobradar.core.domain;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * 公司类型（companies.company_type）。取值与 V2__init_schema.sql 的 CHECK 约束一一对应，
 * 按目标雇主细化，见 docs/data-model.md v0.2 §company_type 枚举说明。
 *
 * <p>两个方向的桥接：
 * <ul>
 *   <li>入库：{@link converter.LowercaseEnumConverter} 转小写匹配 CHECK 约束；</li>
 *   <li>出库（JSON 序列化）：@JsonValue 输出小写（api-design 契约示例为小写）。
 *       反序列化由 application.yml 的 accept-case-insensitive-enums 宽容处理。</li>
 * </ul>
 */
public enum CompanyType {
    INTERNET,       // 互联网
    SOE_CENTRAL,    // 央企
    SOE_LOCAL,      // 地方国企
    INSTITUTE,      // 军工/科研院所（主战场）
    OPERATOR,       // 运营商
    BANK,           // 银行及软开中心
    FOREIGN,        // 外企
    OTHER;

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }
}
