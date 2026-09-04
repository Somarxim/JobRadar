package com.jobradar.core.domain.converter;

import jakarta.persistence.AttributeConverter;

import java.util.Locale;

/**
 * 枚举 ↔ DB 小写串 的通用桥接基类。
 *
 * <p>为什么需要它（面试点）：JPA 的 {@code @Enumerated(EnumType.STRING)} 存的是
 * {@code Enum.name()} 即大写形式（WRITTEN_TEST），而本库 CHECK 约束用小写（written_test）。
 * 二者不一致时有三个选项：
 * <ol>
 *   <li>枚举常量改小写——违背 Java 命名规范，IDE/静态检查全报警；</li>
 *   <li>DB 约束改大写——SQL 世界惯例是小写，且已写库的数据要迁移；</li>
 *   <li>AttributeConverter 显式双向转换（本方案）——JPA 标准扩展点，干净可控。</li>
 * </ol>
 *
 * <p>子类约定：枚举常量名大写下划线形式，toLowerCase 后必须与 DB 取值完全一致
 * （如 SOE_CENTRAL ↔ soe_central）。新增枚举字段时同步检查 CHECK 约束。
 */
public abstract class LowercaseEnumConverter<E extends Enum<E>> implements AttributeConverter<E, String> {

    private final Class<E> enumType;

    protected LowercaseEnumConverter(Class<E> enumType) {
        this.enumType = enumType;
    }

    @Override
    public String convertToDatabaseColumn(E attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public E convertToEntityAttribute(String dbData) {
        return dbData == null ? null : Enum.valueOf(enumType, dbData.toUpperCase(Locale.ROOT));
    }
}
