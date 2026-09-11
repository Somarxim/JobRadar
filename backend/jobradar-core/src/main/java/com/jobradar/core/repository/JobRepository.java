package com.jobradar.core.repository;

import com.jobradar.core.domain.Job;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * 全文检索（W3）：search_vector @@ to_tsquery。为什么用 native 而不是 Criteria：
     * PostgreSQL 的 @@ 匹配运算符不是函数，JPA Criteria 的 cb.function() 无法表达；
     * 走 native 先取 id 集合，再回到 Specification 用 id IN (...) 与其他动态条件组合。
     * tsq 由 JiebaSearchText.tsQuery 生成（词元白名单过滤 + 绑定参数，无注入面）。
     * LIMIT 1000 防御深命中集：超过即截断（搜索意图是前几页，全量枚举无意义）。
     */
    @Query(value = "select j.id from jobs j where j.search_vector @@ to_tsquery('simple', :tsq)"
            + " order by ts_rank(j.search_vector, to_tsquery('simple', :tsq)) desc limit 1000",
            nativeQuery = true)
    List<Long> findIdsByFullText(@Param("tsq") String tsq);

    /** search_text 回填：还没生成索引文本的存量岗位（V2 之后、全文检索上线之前录入的） */
    @Query("select j from Job j join fetch j.company where j.searchText = '' order by j.id")
    List<Job> findSearchTextMissing(Pageable pageable);

    /** 图表统计（W4-3）：在架岗位的公司类型分布 */
    @org.springframework.data.jpa.repository.Query(
            "select j.company.companyType as type, count(j) as cnt from Job j where j.active = true group by j.company.companyType")
    List<TypeCount> countGroupByCompanyType();

    interface TypeCount {
        com.jobradar.core.domain.CompanyType getType();

        long getCnt();
    }
}
