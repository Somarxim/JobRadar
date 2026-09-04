package com.jobradar.core.repository;

import com.jobradar.core.domain.MatchReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MatchReportRepository extends JpaRepository<MatchReport, Long> {

    /** 岗位详情页展示该岗位的历次匹配报告，最新在前（对应 idx_match_job 索引） */
    List<MatchReport> findByJobIdOrderByCreatedAtDesc(Long jobId);
}
