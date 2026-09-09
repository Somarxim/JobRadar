package com.jobradar.app.config;

import com.jobradar.core.service.RecommendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每日推荐调度（W3-3）。默认每天 07:45 跑一轮——爬虫 07:30 批次入库之后，
 * 用户早上打开 Dashboard 时推荐已就绪。
 *
 * <p>与 CrawlScheduler 同一约定：cron 走配置（jobradar.recommend.cron）、
 * enabled 开关可整体关停；调度器只做触发，逻辑全在 core 的 RecommendService，
 * 与手动触发（POST /api/recommendations/run）走同一入口。
 */
@Component
@ConditionalOnProperty(prefix = "jobradar.recommend", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RecommendScheduler {

    private static final Logger log = LoggerFactory.getLogger(RecommendScheduler.class);

    private final RecommendService recommendService;

    public RecommendScheduler(RecommendService recommendService) {
        this.recommendService = recommendService;
    }

    @Scheduled(cron = "${jobradar.recommend.cron:0 45 7 * * *}")
    public void dailyRecommend() {
        log.info("每日推荐调度启动");
        var report = recommendService.runPipeline();
        log.info("每日推荐调度完成：候选 {} → 推荐 {}", report.candidates(), report.recommended());
    }
}
