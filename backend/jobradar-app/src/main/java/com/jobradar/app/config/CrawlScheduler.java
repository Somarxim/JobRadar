package com.jobradar.app.config;

import com.jobradar.core.crawl.CrawlerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 爬虫每日调度（W3-1）。默认每天 07:30 跑一轮启用中的源——
 * 早间抓完，用户打开 Dashboard 时看到的是当日新岗位。
 *
 * <p>设计点：
 * <ul>
 *   <li>cron 走配置（jobradar.crawler.cron），不写死——调度策略是运维决策；</li>
 *   <li>enabled 开关（默认开）：本地调试/长期不用时可关，避免无意义外呼；</li>
 *   <li>调度器只做触发，逻辑全在 core 的 CrawlerService——
 *       REST 手动触发（POST /api/crawl/run）与定时触发走同一入口，行为一致。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "jobradar.crawler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CrawlScheduler {

    private static final Logger log = LoggerFactory.getLogger(CrawlScheduler.class);

    private final CrawlerService crawlerService;

    public CrawlScheduler(CrawlerService crawlerService) {
        this.crawlerService = crawlerService;
    }

    @Scheduled(cron = "${jobradar.crawler.cron:0 30 7 * * *}")
    public void dailyCrawl() {
        log.info("每日爬虫调度启动");
        var summary = crawlerService.runAll();
        log.info("每日爬虫调度完成：新增 {} 条岗位", summary.totalCreated());
    }
}
