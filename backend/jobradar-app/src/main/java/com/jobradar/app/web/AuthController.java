package com.jobradar.app.web;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证状态查询：前端启动时调 /api/auth/me 判断登录态，
 * 决定渲染主界面还是跳登录页。
 */
@RestController
@RequestMapping("/api")
public class AuthController {

    /** 存活探针：无认证、无数据库依赖，Docker healthcheck / Caddy / 监控用 */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "up");
    }

    @GetMapping("/auth/me")
    public ResponseEntity<?> me(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(401).body(Map.of("detail", "未登录"));
        }
        return ResponseEntity.ok(Map.of("username", auth.getName()));
    }
}
