package com.jobradar.core.llm;

/** LLM 模型配置（application.yml jobradar.llm.* 段绑定）。apiKey 为空 = 该模型未启用（优雅降级） */
public record LlmModelConfig(String baseUrl, String apiKey, String model) {

    public boolean available() {
        return apiKey != null && !apiKey.isBlank() && model != null && !model.isBlank();
    }
}
