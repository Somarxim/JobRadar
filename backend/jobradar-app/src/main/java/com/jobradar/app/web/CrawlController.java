package com.jobradar.app.web;

import com.jobradar.core.crawl.CrawlerService;
import com.jobradar.core.crawl.CrawlerService.CrawlRunSummary;
import com.jobradar.core.domain.CrawlSource;
import com.jobradar.core.repository.CrawlSourceRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 爬虫管理端点（W3-1）：
 * - GET  /api/crawl/sources  源列表（含最近抓取时间，只读免令牌）
 * - POST /api/crawl/run      手动触发一轮（可指定 source_id 调试单源；
 *                            写操作走 LocalTokenFilter：前端 Origin 或 X-Local-Token）
 */
@RestController
@RequestMapping("/api/crawl")
public class CrawlController {

    private final CrawlSourceRepository sourceRepository;
    private final CrawlerService crawlerService;

    public CrawlController(CrawlSourceRepository sourceRepository, CrawlerService crawlerService) {
        this.sourceRepository = sourceRepository;
        this.crawlerService = crawlerService;
    }

    public record SourceView(Long id, String name, String url, String parser,
                             String category, boolean enabled, Instant lastCrawledAt) {
    }

    @GetMapping("/sources")
    public List<SourceView> sources() {
        return sourceRepository.findAll().stream()
                .map(CrawlController::toView)
                .toList();
    }

    @PostMapping("/run")
    public CrawlRunSummary run(@RequestParam(name = "source_id", required = false) Long sourceId) {
        return sourceId != null ? crawlerService.runOne(sourceId) : crawlerService.runAll();
    }

    private static SourceView toView(CrawlSource s) {
        return new SourceView(s.getId(), s.getName(), s.getUrl(), s.getParser(),
                s.getCategory().toJson(), s.isEnabled(), s.getLastCrawledAt());
    }
}
