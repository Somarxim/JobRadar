package com.jobradar.core.repository;

import com.jobradar.core.domain.ApplicationEvent;
import com.jobradar.core.domain.ApplicationStage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ApplicationEventRepository extends JpaRepository<ApplicationEvent, Long> {

    /** 岗位详情页的流转时间线，最新在前 */
    List<ApplicationEvent> findByApplicationIdOrderByCreatedAtDesc(Long applicationId);

    /** Dashboard 本周新增投递数（to_stage=applied 且发生在本周内） */
    long countByToStageAndCreatedAtGreaterThanEqual(ApplicationStage toStage, Instant since);
}
