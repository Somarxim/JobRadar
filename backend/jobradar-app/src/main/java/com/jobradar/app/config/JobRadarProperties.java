package com.jobradar.app.config;

import com.jobradar.core.llm.LlmModelConfig;
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
public record JobRadarProperties(Security security, Cors cors, Llm llm, Storage storage) {

    public record Security(String localToken) {
    }

    /** allowedOrigins 来自 yml 逗号分隔串（Spring 自动按逗号拆成 List） */
    public record Cors(List<String> allowedOrigins) {
    }

    /**
     * LLM 模型路由（agent-design.md §1.2）：
     * parse=文本结构化解析（DeepSeek，中文好、便宜）；vision=多模态（DashScope qwen-vl，海报图片）。
     * key 为空即未启用，对应能力优雅降级（ingest 回到 hints 必填）。
     * dailyTokenLimit=每日 token 成本闸（null 不限），超闸后所有 LLM 任务拒发并记失败账。
     */
    public record Llm(LlmModelConfig parse, LlmModelConfig vision, Long dailyTokenLimit) {
    }

    /** 本地文件存储：简历 PDF 原件目录（单用户本地应用，直接落文件系统，默认值见 application.yml） */
    public record Storage(String resumeDir) {
    }
}
