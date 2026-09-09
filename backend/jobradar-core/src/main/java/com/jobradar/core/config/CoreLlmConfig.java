package com.jobradar.core.config;

import com.jobradar.core.llm.LlmModelConfig;
import com.jobradar.core.llm.LlmService;
import com.jobradar.core.repository.LlmUsageRepository;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * LLM 装配（W4-1 从 jobradar-app 下沉到 core）：按配置手工构造 LlmService
 * （多模型实例，不走 spring.ai 自动装配）。未配置 key 时模型为 null，能力优雅降级。
 *
 * <p>为什么在 core：W4 起 jobradar-mcp-server 进程也需要 LlmService（match_job 工具），
 * 两个进程共享同一份 jobradar.llm.* 配置（同一组环境变量注入）；
 * 放在 core 后 app/mcp-server 组件扫描都能装配，避免重复定义 bean。
 */
@Configuration
@EnableConfigurationProperties(CoreLlmConfig.LlmProps.class)
public class CoreLlmConfig {

    @Bean
    public LlmService llmService(LlmUsageRepository usageRepository, PlatformTransactionManager txManager,
                                 LlmProps props) {
        return new LlmService(usageRepository, txManager, props.parse(), props.vision(),
                props.dailyTokenLimit());
    }

    /** jobradar.llm.* 段绑定（parse=文本解析 / vision=多模态 / daily-token-limit=每日成本闸） */
    @ConfigurationProperties(prefix = "jobradar.llm")
    public record LlmProps(LlmModelConfig parse, LlmModelConfig vision, Long dailyTokenLimit) {
    }
}
