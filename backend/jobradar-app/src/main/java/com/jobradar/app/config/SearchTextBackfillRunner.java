package com.jobradar.app.config;

import com.jobradar.core.service.SearchTextBackfillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时回填存量岗位的 search_text（全文检索上线前的历史数据）。
 * 无存量时第一轮查询即返回 0，开销一次空查询；有存量则分批补齐后自然停止。
 * 幂等：只处理 search_text 为空的行，重复启动无副作用。
 */
@Component
public class SearchTextBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SearchTextBackfillRunner.class);
    /** 批次上限保险丝：防止异常情况下死循环（单用户规模永远不该碰到） */
    private static final int MAX_BATCHES = 500;

    private final SearchTextBackfillService backfillService;

    public SearchTextBackfillRunner(SearchTextBackfillService backfillService) {
        this.backfillService = backfillService;
    }

    @Override
    public void run(ApplicationArguments args) {
        int total = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            int n = backfillService.backfillBatch();
            total += n;
            if (n == 0) {
                break;
            }
        }
        if (total > 0) {
            log.info("search_text 回填完成：共 {} 条岗位重建全文索引文本", total);
        }
    }
}
