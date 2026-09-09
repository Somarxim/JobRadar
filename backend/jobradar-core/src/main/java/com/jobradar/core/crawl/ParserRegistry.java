package com.jobradar.core.crawl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 解析器注册表：crawl_sources.parser → SiteParser Bean。
 * Spring 自动注入所有 SiteParser 实现（List 注入是框架原生支持的集合装配），
 * 新增解析器实现类即自动注册，无需改动此处。
 */
@Component
public class ParserRegistry {

    private static final Logger log = LoggerFactory.getLogger(ParserRegistry.class);

    private final Map<String, SiteParser> parsers;

    public ParserRegistry(List<SiteParser> implementations) {
        this.parsers = implementations.stream()
                .collect(Collectors.toMap(SiteParser::name, Function.identity()));
        log.info("站点解析器注册：{}", parsers.keySet());
    }

    /** 找不到解析器视为源配置错误，抛异常由管线隔离捕获 */
    public SiteParser get(String parserName) {
        SiteParser p = parsers.get(parserName);
        if (p == null) {
            throw new IllegalStateException("未注册的解析器: " + parserName
                    + "（已注册: " + parsers.keySet() + "）");
        }
        return p;
    }
}
