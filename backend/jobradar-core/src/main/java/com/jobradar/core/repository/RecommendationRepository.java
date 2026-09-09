package com.jobradar.core.repository;

import com.jobradar.core.domain.Recommendation;
import com.jobradar.core.domain.RecommendationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {

    /** 今日推荐列表（按位次排序） */
    List<Recommendation> findByRecDateOrderByRankAsc(LocalDate recDate);

    /** 今日待处理推荐（Dashboard 只展示这一炉；已处理的留库不展示） */
    List<Recommendation> findByRecDateAndStatusOrderByRankAsc(LocalDate recDate, RecommendationStatus status);

    /** 防重复推荐窗口：近 N 天推荐过的岗位 id 批量取回（避免逐岗 exists 的 N+1） */
    @Query("select r.job.id from Recommendation r where r.recDate >= :since")
    List<Long> findJobIdsByRecDateGreaterThanEqual(LocalDate since);

    /** 批量删除前的引用检查：这些岗位里哪些被推荐过（投影只取 id） */
    @Query("select distinct r.job.id from Recommendation r where r.job.id in :jobIds")
    List<Long> findJobIdsByJobIdIn(Collection<Long> jobIds);

    /** Dashboard 统计：待处理的推荐条数 */
    long countByStatus(RecommendationStatus status);

    /** 周报（W4-2）：区间内生成的推荐总数 */
    long countByRecDateBetween(LocalDate from, LocalDate to);

    /** 周报（W4-2）：区间内指定状态的推荐数（采纳率=ACCEPTED/总数） */
    long countByRecDateBetweenAndStatus(LocalDate from, LocalDate to, RecommendationStatus status);
}
