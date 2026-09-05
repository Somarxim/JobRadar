package com.jobradar.core.repository;

import com.jobradar.core.domain.LlmUsage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmUsageRepository extends JpaRepository<LlmUsage, Long> {
}
