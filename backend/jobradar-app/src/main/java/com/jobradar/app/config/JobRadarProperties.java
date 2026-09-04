package com.jobradar.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 应用自定义配置绑定（application.yml 的 jobradar.* 段）。
 *
 * <p>@ConfigurationProperties vs @Value（面试点）：前者把一组配置绑定成类型安全的对象
 * （可校验、可在 IDE 里跳转、结构清晰），适合成组配置；@Value 适合零散的单个值。
 * 配合 spring-boot-configuration-processor 还能生成配置元数据（yml 里自动补全）。
 */
@ConfigurationProperties(prefix = "jobradar")
public record JobRadarProperties(Security security, Cors cors) {

    public record Security(String localToken) {
    }

    /** allowedOrigins 来自 yml 逗号分隔串（Spring 自动按逗号拆成 List） */
    public record Cors(List<String> allowedOrigins) {
    }
}
