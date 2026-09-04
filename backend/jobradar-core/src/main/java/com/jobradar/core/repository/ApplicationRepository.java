package com.jobradar.core.repository;

import com.jobradar.core.domain.Application;
import com.jobradar.core.domain.ApplicationStage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

    Optional<Application> findByJobId(Long jobId);

    List<Application> findByStage(ApplicationStage stage);

    /** 岗位列表页批量装配阶段信息，避免逐行查询（N+1） */
    List<Application> findByJobIdIn(java.util.Collection<Long> jobIds);

    /** 日历/待办：所有设置了下一步动作时间的投递 */
    List<Application> findByNextActionAtNotNullOrderByNextActionAt();

    /** 日历：计划投递日落在区间内的投递 */
    List<Application> findByPlannedAtBetween(java.time.LocalDate from, java.time.LocalDate to);

    /** 日历：下一步动作时间落在区间内的投递 */
    List<Application> findByNextActionAtBetween(java.time.Instant from, java.time.Instant to);

    /**
     * Dashboard 漏斗：各阶段计数。
     * 接口投影（interface projection）教学点：不需要为统计结果建实体，
     * 定义 getter 接口 + JPQL 别名（as stage / as cnt），Spring Data 自动生成代理实现。
     */
    @Query("select a.stage as stage, count(a) as cnt from Application a group by a.stage")
    List<StageCount> countGroupByStage();

    interface StageCount {
        ApplicationStage getStage();

        long getCnt();
    }
}
