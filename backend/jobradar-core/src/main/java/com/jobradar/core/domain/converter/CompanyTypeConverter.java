package com.jobradar.core.domain.converter;

import com.jobradar.core.domain.CompanyType;
import jakarta.persistence.Converter;

/** autoApply=true：所有 CompanyType 类型字段自动套用，无需逐个 @Convert 声明。 */
@Converter(autoApply = true)
public class CompanyTypeConverter extends LowercaseEnumConverter<CompanyType> {
    public CompanyTypeConverter() {
        super(CompanyType.class);
    }
}
