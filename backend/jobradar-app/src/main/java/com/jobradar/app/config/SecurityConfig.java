package com.jobradar.app.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;

import java.nio.charset.StandardCharsets;

/**
 * 登录认证配置：单用户 + Session Cookie。
 *
 * <p>设计取舍（面试点）：
 * <ul>
 *   <li><b>Session 而非 JWT</b>：个人单用户场景下 JWT 引入额外复杂度（刷新、黑名单、密钥轮换），
 *       Spring Security 的 HttpSession 由容器自动管理，登录态就是服务端一条内存记录，登出即销毁。</li>
 *   <li><b>InMemoryUserDetailsManager</b>：当前只有一人使用，无需用户注册/多租户/权限矩阵；
 *       密码走环境变量注入，不落入版本控制。</li>
 *   <li><b>authEnabled 开关</b>：开发环境设为 false 后所有请求放行，避免前端 dev server
 *       频繁热重载时反复跳登录页打断开发流。</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    private static final String USERNAME = "admin";

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(JobRadarProperties properties, PasswordEncoder encoder) {
        String raw = properties.security() != null && properties.security().password() != null
                ? properties.security().password()
                : "jobradar";
        return new InMemoryUserDetailsManager(
                User.withUsername(USERNAME)
                        .password(encoder.encode(raw))
                        .roles("USER")
                        .build()
        );
    }

    @Bean
    public AuthenticationManager authenticationManager(
            UserDetailsService userDetailsService, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    /**
     * 生产环境：启用认证（默认行为，authEnabled 缺省或 true 时生效）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "jobradar.security", name = "auth-enabled", havingValue = "true", matchIfMissing = true)
    public SecurityFilterChain securedFilterChain(HttpSecurity http,
                                                   AuthenticationManager authenticationManager,
                                                   JobRadarProperties properties) throws Exception {
        return configureCommon(http, properties)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex.authenticationEntryPoint(jsonEntryPoint()))
                .formLogin(form -> form
                        .loginProcessingUrl("/api/auth/login")
                        .successHandler(jsonSuccessHandler())
                        .failureHandler(jsonFailureHandler())
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler(jsonLogoutSuccessHandler())
                        .permitAll()
                )
                .authenticationManager(authenticationManager)
                .build();
    }

    /**
     * 开发环境：authEnabled=false 时所有请求放行。
     */
    @Bean
    @ConditionalOnProperty(prefix = "jobradar.security", name = "auth-enabled", havingValue = "false")
    public SecurityFilterChain openFilterChain(HttpSecurity http, JobRadarProperties properties) throws Exception {
        return configureCommon(http, properties)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    /** CORS + CSRF + Session 的公共配置 */
    private HttpSecurity configureCommon(HttpSecurity http, JobRadarProperties properties) throws Exception {
        return http
                .csrf(csrf -> csrf.disable()) // API 模式关闭 CSRF（前后端分离 + Cookie 跨域场景下维护 CSRF token 成本高）
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .cors(cors -> {}); // 启用 CORS，具体规则由 WebConfig.addCorsMappings() 提供
    }

    /* ---------- JSON 响应处理器 ---------- */

    private AuthenticationEntryPoint jsonEntryPoint() {
        return (req, res, ex) -> {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            res.setCharacterEncoding(StandardCharsets.UTF_8.name());
            res.getWriter().write("{\"detail\":\"未登录，请先登录\"}");
        };
    }

    private AuthenticationSuccessHandler jsonSuccessHandler() {
        return (req, res, auth) -> {
            res.setStatus(HttpServletResponse.SC_OK);
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            res.setCharacterEncoding(StandardCharsets.UTF_8.name());
            res.getWriter().write("{\"username\":\"" + auth.getName() + "\"}");
        };
    }

    private AuthenticationFailureHandler jsonFailureHandler() {
        return (req, res, ex) -> {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            res.setCharacterEncoding(StandardCharsets.UTF_8.name());
            res.getWriter().write("{\"detail\":\"用户名或密码错误\"}");
        };
    }

    private LogoutSuccessHandler jsonLogoutSuccessHandler() {
        return (req, res, auth) -> {
            res.setStatus(HttpServletResponse.SC_OK);
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            res.setCharacterEncoding(StandardCharsets.UTF_8.name());
            res.getWriter().write("{\"ok\":true}");
        };
    }
}
