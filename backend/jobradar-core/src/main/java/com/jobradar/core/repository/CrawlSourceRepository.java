package com.jobradar.core.repository;

import com.jobradar.core.domain.CrawlSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CrawlSourceRepository extends JpaRepository<CrawlSource, Long> {

    /** W3 调度器每轮取启用中的源 */
    List<CrawlSource> findByEnabledTrue();
}
