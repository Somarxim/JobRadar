package com.jobradar.core.repository;

import com.jobradar.core.domain.MatchReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MatchReportRepository extends JpaRepository<MatchReport, Long> {

    /** 岗位详情页展示该岗位的历次匹配报告，最新在前（对应 idx_match_job 索引） */
    List<MatchReport> findByJobIdOrderByCreatedAtDesc(Long jobId);

    /** 24h 缓存复用（agent-design §4）：同 (job, resume) 取最新一份 */
    Optional<MatchReport> findFirstByJobIdAndResumeIdOrderByCreatedAtDesc(Long jobId, Long resumeId);
}
