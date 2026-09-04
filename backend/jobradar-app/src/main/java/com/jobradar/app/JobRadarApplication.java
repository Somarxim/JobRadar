package com.jobradar.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * JobRadar Web 应用入口（jobradar-app 模块）。
 *
 * <p>注解的教学说明：
 * <ul>
 *   <li>{@code @SpringBootApplication} = @Configuration + @EnableAutoConfiguration + @ComponentScan。
 *       组件扫描默认以本类所在包（com.jobradar.app）为根——core 模块的类在 com.jobradar.core 包下，
 *       因此需要显式指定 scanBasePackages 覆盖两个包，否则 core 的 Service 不会被装配。</li>
 *   <li><b>高频坑</b>：scanBasePackages 只影响 @ComponentScan，<b>不影响</b> JPA 的实体与
 *       Repository 扫描——后两者默认取"主类所在包"（AutoConfigurationPackages 机制）。
 *       实体在 com.jobradar.core.domain，所以必须显式 @EntityScan + @EnableJpaRepositories，
 *       否则启动报 "Not a managed type"。</li>
 *   <li>{@code @EnableScheduling}：开启 @Scheduled 定时任务支持（W3 每日推荐管线使用，
 *       见 docs/agent-design.md §5）。</li>
 * </ul>
 */
@SpringBootApplication(scanBasePackages = {"com.jobradar.app", "com.jobradar.core"})
@EntityScan("com.jobradar.core.domain")
@EnableJpaRepositories("com.jobradar.core.repository")
@EnableScheduling
public class JobRadarApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobRadarApplication.class, args);
    }
}
