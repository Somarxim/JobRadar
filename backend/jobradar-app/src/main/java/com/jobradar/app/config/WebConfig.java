package com.jobradar.app.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：CORS 放行（api-design.md §3：vite dev server + Chrome 插件）。
 * 未引入 Spring Security——本地单用户应用用不到它的认证授权体系，
 * CORS 与简易令牌用 MVC 配置 + 一个过滤器即可覆盖，依赖最少（ADR：不引入非必要依赖）。
 */
@Configuration
@EnableConfigurationProperties(JobRadarProperties.class)
public class WebConfig implements WebMvcConfigurer {

    private final JobRadarProperties properties;

    public WebConfig(JobRadarProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        var registration = registry.addMapping("/api/**")
                .allowedMethods("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
        if (properties.cors() != null && properties.cors().allowedOrigins() != null) {
            registration.allowedOrigins(properties.cors().allowedOrigins().toArray(String[]::new));
        }
        // 插件来源 origin 形如 chrome-extension://<随机 id>，id 安装时才确定，只能用模式放行
        registration.allowedOriginPatterns("chrome-extension://*");
    }
}
