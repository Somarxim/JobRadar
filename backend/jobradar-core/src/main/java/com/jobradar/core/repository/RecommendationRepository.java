package com.jobradar.core.repository;

import com.jobradar.core.domain.Recommendation;
import com.jobradar.core.domain.RecommendationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {

    /** 今日推荐列表（按位次排序） */
    List<Recommendation> findByRecDateOrderByRankAsc(LocalDate recDate);

    /** 防重复推荐窗口：近 N 天推荐过的岗位 id 批量取回（避免逐岗 exists 的 N+1） */
    @Query("select r.job.id from Recommendation r where r.recDate >= :since")
    List<Long> findJobIdsByRecDateGreaterThanEqual(LocalDate since);

    /** Dashboard 统计：待处理的推荐条数 */
    long countByStatus(RecommendationStatus status);
}
