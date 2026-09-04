package com.jobradar.core.repository;

import com.jobradar.core.domain.Recommendation;
import com.jobradar.core.domain.RecommendationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {

    /** Dashboard"今日推荐"区：按日期取当日推荐位次 */
    List<Recommendation> findByRecDateOrderByRank(LocalDate recDate);

    /** Dashboard 未读推荐数 */
    long countByStatus(RecommendationStatus status);
}
