package com.jobradar.core.repository;

import com.jobradar.core.domain.ApplicationEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApplicationEventRepository extends JpaRepository<ApplicationEvent, Long> {

    /** 岗位详情页的流转时间线，最新在前 */
    List<ApplicationEvent> findByApplicationIdOrderByCreatedAtDesc(Long applicationId);
}
