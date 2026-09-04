package com.jobradar.core.repository;

import com.jobradar.core.domain.Job;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * JpaSpecificationExecutor 的教学点：W1 岗位库的"标题/城市/来源/公司类型"组合筛选
 * 是动态条件——为每种组合写 Repository 方法会爆炸，if-else 拼 JPQL 又脏。
 * Specification 用 Criteria API 类型安全地动态拼装 WHERE，是 Spring Data 的标准解法。
 */
public interface JobRepository extends JpaRepository<Job, Long>, JpaSpecificationExecutor<Job> {

    /** 录入前去重判断（dedupe_hash 唯一约束的应用层前置检查，约束兜底并发） */
    boolean existsByDedupeHash(String dedupeHash);

    Optional<Job> findByDedupeHash(String dedupeHash);

    /** Dashboard 本周新增岗位数 */
    long countByCreatedAtGreaterThanEqual(Instant since);

    /** DDL 倒计时列表（只取未归档岗位，对应 idx_jobs_deadline 部分索引） */
    List<Job> findByActiveTrueAndDeadlineGreaterThanEqualOrderByDeadlineAsc(LocalDate from,
                                                                            Pageable pageable);

    /** 日历：截止日落在指定区间内的岗位 */
    List<Job> findByActiveTrueAndDeadlineBetween(LocalDate from, LocalDate to);
}
