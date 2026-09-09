package com.jobradar.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobradar.core.llm.LlmService;
import com.jobradar.core.repository.ResumeRepository;
import com.jobradar.core.service.ResumeService;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 简历装配（W4-1 从 jobradar-app 下沉到 core）：ResumeService 构造参数含配置值
 * （resumeDir），组件扫描表达不了，显式 @Bean。ObjectMapper 用 Spring Boot 全局实例。
 *
 * <p>为什么在 core：mcp-server 的 resume/default resource 也要读简历画像，
 * 与 CoreLlmConfig 同理——共享配置段，双进程各自装配。
 */
@Configuration
@EnableConfigurationProperties(CoreResumeConfig.StorageProps.class)
public class CoreResumeConfig {

    @Bean
    public ResumeService resumeService(ResumeRepository resumeRepository, LlmService llmService,
                                       ObjectMapper objectMapper, StorageProps props) {
        return new ResumeService(resumeRepository, llmService, objectMapper, props.resumeDir());
    }

    /** jobradar.storage.* 段绑定（简历 PDF 原件目录，默认值见各进程 application.yml） */
    @ConfigurationProperties(prefix = "jobradar.storage")
    public record StorageProps(String resumeDir) {
    }
}
