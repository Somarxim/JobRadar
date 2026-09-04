package com.jobradar.core.domain.converter;

import com.jobradar.core.domain.ApplicationStage;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class ApplicationStageConverter extends LowercaseEnumConverter<ApplicationStage> {
    public ApplicationStageConverter() {
        super(ApplicationStage.class);
    }
}
