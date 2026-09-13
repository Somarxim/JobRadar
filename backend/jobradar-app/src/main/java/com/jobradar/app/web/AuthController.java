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
 * （/api/health 存活探针在 HealthController，勿在此重复声明——同路径双映射会导致启动失败）
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(401).body(Map.of("detail", "未登录"));
        }
        return ResponseEntity.ok(Map.of("username", auth.getName()));
    }
}
