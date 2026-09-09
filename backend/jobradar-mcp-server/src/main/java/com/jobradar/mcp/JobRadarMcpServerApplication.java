package com.jobradar.mcp;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * JobRadar MCP Server 入口（jobradar-mcp-server 模块，W4 实现）。
 *
 * <p>与 Web 应用的关键区别：MCP 以 stdio（标准输入输出）与 Claude Desktop 通信，
 * 不需要任何 HTTP 端口，因此 {@code webApplicationType = NONE}——Spring 容器
 * 照常装配（core 的 Service 可用），但不启动内嵌 Tomcat。
 *
 * <p>注意：stdio 模式下任何打印到 stdout 的内容都会污染协议流，
 * 日志必须走 stderr/文件（logback 配置在 W4 一并处理）。
 */
@SpringBootApplication(scanBasePackages = {"com.jobradar.mcp", "com.jobradar.core"})
// scanBasePackages 只影响 @ComponentScan，不影响 JPA 扫描（AutoConfigurationPackages 机制）：
// 实体在 com.jobradar.core.domain、Repository 在 com.jobradar.core.repository，必须显式指定
@EntityScan("com.jobradar.core.domain")
@EnableJpaRepositories("com.jobradar.core.repository")
// 注意：不开 @EnableScheduling——爬虫/推荐定时任务归 jobradar-app 单一调度者，
// MCP 进程即使意外加载到 Scheduler 配置类也不会触发
public class JobRadarMcpServerApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(JobRadarMcpServerApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }
}
