package com.jobradar.app.web;

import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查端点：骨架阶段用于验证「应用启动 + Flyway 迁移 + Web 层」全链路可用。
 * 后续可扩展为返回 DB 连通性、LLM Provider 可用性等深度检查。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "service", "jobradar-app",
                "time", Instant.now().toString());
    }
}
