package com.jobradar.core.crawl;

import com.jobradar.core.domain.CrawlSource;

import java.util.List;

/**
 * 站点解析器 SPI（roadmap 风险登记：源插件化，单源失败隔离）。
 *
 * <p>每个实现绑定一个 parser 标识（crawl_sources.parser 列），
 * 由 {@link ParserRegistry} 注册与查找。新增站点 = 新增一个实现类
 * （或给通用 {@link SelectorListParser} 配一套 meta），不动管线。
 */
public interface SiteParser {

    /** 解析器标识，对应 crawl_sources.parser */
    String name();

    /**
     * 从列表页 HTML 提取原始岗位条目。
     * 实现只负责「HTML → 条目」，抓取/清洗/去重/落库都在管线层。
     * 解析失败抛异常即可——管线按源隔离捕获，不影响其他源。
     */
    List<RawJobPosting> parse(CrawlSource source, String html);
}
