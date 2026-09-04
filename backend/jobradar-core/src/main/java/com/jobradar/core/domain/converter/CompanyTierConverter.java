package com.jobradar.core.domain.converter;

import com.jobradar.core.domain.CompanyTier;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class CompanyTierConverter extends LowercaseEnumConverter<CompanyTier> {
    public CompanyTierConverter() {
        super(CompanyTier.class);
    }
}
