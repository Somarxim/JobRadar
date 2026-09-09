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

    /** 周报（W4-2）：区间新增岗位数 */
    long countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(Instant from, Instant toExclusive);

    /** DDL 倒计时列表（只取未归档岗位，对应 idx_jobs_deadline 部分索引） */
    List<Job> findByActiveTrueAndDeadlineGreaterThanEqualOrderByDeadlineAsc(LocalDate from,
                                                                            Pageable pageable);

    /** 日历：截止日落在指定区间内的岗位 */
    List<Job> findByActiveTrueAndDeadlineBetween(LocalDate from, LocalDate to);

    /** 每日推荐管线候选池：近 N 天新入库的岗位（爬虫/手动录入都算） */
    List<Job> findByActiveTrueAndCreatedAtGreaterThanEqual(Instant since);

    /** 图表统计（W4-3）：区间入库岗位（含已归档——当日"收录"动作不因事后清理而抹除） */
    List<Job> findByCreatedAtGreaterThanEqual(Instant since);

    /** 图表统计（W4-3）：在架岗位的公司类型分布 */
    @org.springframework.data.jpa.repository.Query(
            "select j.company.companyType as type, count(j) as cnt from Job j where j.active = true group by j.company.companyType")
    List<TypeCount> countGroupByCompanyType();

    interface TypeCount {
        com.jobradar.core.domain.CompanyType getType();

        long getCnt();
    }
}
