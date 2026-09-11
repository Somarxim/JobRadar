package com.jobradar.app.config;

import com.jobradar.core.service.CompanyTypeBackfillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时重判存量公司的企业性质（自动分类上线前的历史数据全是 OTHER）。
 * 幂等：只处理仍为 OTHER 的行；无存量时开销一次空查询。
 */
@Component
public class CompanyTypeBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CompanyTypeBackfillRunner.class);

    private final CompanyTypeBackfillService backfillService;

    public CompanyTypeBackfillRunner(CompanyTypeBackfillService backfillService) {
        this.backfillService = backfillService;
    }

    @Override
    public void run(ApplicationArguments args) {
        int[] result = backfillService.reclassifyOthers();
        if (result[1] > 0) {
            log.info("企业性质回填完成：检查 {} 家存量公司，{} 家从 OTHER 改判", result[0], result[1]);
        }
    }
}
