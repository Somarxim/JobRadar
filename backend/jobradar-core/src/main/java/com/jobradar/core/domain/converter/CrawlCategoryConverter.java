package com.jobradar.core.domain.converter;

import com.jobradar.core.domain.CrawlCategory;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class CrawlCategoryConverter extends LowercaseEnumConverter<CrawlCategory> {
    public CrawlCategoryConverter() {
        super(CrawlCategory.class);
    }
}
