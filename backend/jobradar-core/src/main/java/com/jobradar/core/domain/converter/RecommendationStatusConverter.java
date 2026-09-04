package com.jobradar.core.domain.converter;

import com.jobradar.core.domain.RecommendationStatus;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class RecommendationStatusConverter extends LowercaseEnumConverter<RecommendationStatus> {
    public RecommendationStatusConverter() {
        super(RecommendationStatus.class);
    }
}
