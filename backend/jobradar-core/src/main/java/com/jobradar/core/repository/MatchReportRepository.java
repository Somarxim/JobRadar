package com.jobradar.core.repository;

import com.jobradar.core.domain.MatchReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MatchReportRepository extends JpaRepository<MatchReport, Long> {

    /** 岗位详情页展示该岗位的历次匹配报告，最新在前（对应 idx_match_job 索引） */
    List<MatchReport> findByJobIdOrderByCreatedAtDesc(Long jobId);

    /** 24h 缓存复用（agent-design §4）：同 (job, resume) 取最新一份 */
    Optional<MatchReport> findFirstByJobIdAndResumeIdOrderByCreatedAtDesc(Long jobId, Long resumeId);

    /** 批量删除前的引用检查：这些岗位里哪些有匹配报告（投影只取 id，不拉实体） */
    @Query("select distinct m.job.id from MatchReport m where m.job.id in :jobIds")
    List<Long> findJobIdsByJobIdIn(Collection<Long> jobIds);
}
