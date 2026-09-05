package com.jobradar.core.repository;

import com.jobradar.core.domain.LlmUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface LlmUsageRepository extends JpaRepository<LlmUsage, Long> {

    /** 成本闸：统计某时刻以来的累计 token（失败行 token 为 null，sum 自动跳过） */
    @Query("select coalesce(sum(u.totalTokens), 0) from LlmUsage u where u.createdAt >= :since")
    long sumTokensSince(@Param("since") Instant since);
}
