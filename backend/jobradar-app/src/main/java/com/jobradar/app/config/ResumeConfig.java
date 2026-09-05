package com.jobradar.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobradar.core.llm.LlmService;
import com.jobradar.core.repository.ResumeRepository;
import com.jobradar.core.service.ResumeService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 简历装配：ResumeService 构造参数含配置值（resumeDir），与 LlmService 同模式手工构造。
 * ObjectMapper 直接用 Spring Boot 自动装配的全局实例（与 JSON 序列化行为保持一致）。
 */
@Configuration
public class ResumeConfig {

    @Bean
    public ResumeService resumeService(ResumeRepository resumeRepository, LlmService llmService,
                                       ObjectMapper objectMapper, JobRadarProperties props) {
        return new ResumeService(resumeRepository, llmService, objectMapper, props.storage().resumeDir());
    }
}
