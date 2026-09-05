package com.jobradar.app.config;

import com.jobradar.core.llm.LlmService;
import com.jobradar.core.repository.LlmUsageRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 装配：按配置手工构造 LlmService（多模型实例，不走 spring.ai 自动装配）。
 * 未配置 key 时 LlmService 内部对应模型为 null，相关能力优雅降级。
 */
@Configuration
public class LlmConfig {

    @Bean
    public LlmService llmService(LlmUsageRepository usageRepository, JobRadarProperties props) {
        return new LlmService(usageRepository, props.llm().parse(), props.llm().vision());
    }
}
