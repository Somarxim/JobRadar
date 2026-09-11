package com.jobradar.core.service;

import com.jobradar.core.domain.Job;
import com.jobradar.core.repository.JobRepository;
import com.jobradar.core.util.JiebaSearchText;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * search_text 存量回填：全文检索上线前录入的岗位 search_text 为空（V2 建表默认值），
 * 启动时由 app 模块的 SearchTextBackfillRunner 分批驱动本服务补齐。
 * 应用层回填（而非 DB 迁移）的原因：中文分词在应用侧（jieba），SQL 迁移做不了。
 */
@Service
public class SearchTextBackfillService {

    private static final int BATCH_SIZE = 200;

    private final JobRepository jobRepository;

    public SearchTextBackfillService(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    /**
     * 回填一批（≤200 条）；返回处理条数，0 表示无存量、调用方停止。
     * 分批 + 每批一个事务：避免全量回填的长事务占锁，也保证中断后已完成的批次不回滚。
     */
    @Transactional
    public int backfillBatch() {
        List<Job> batch = jobRepository.findSearchTextMissing(PageRequest.of(0, BATCH_SIZE));
        for (Job job : batch) {
            job.setSearchText(JiebaSearchText.indexText(
                    job.getCompany().getName(), job.getTitle(), job.getCity(),
                    job.getJdSummary(), job.getJdText()));
            // 托管实体脏检查自动 UPDATE，无需显式 save
        }
        return batch.size();
    }
}
