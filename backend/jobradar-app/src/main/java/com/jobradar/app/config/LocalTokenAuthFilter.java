package com.jobradar.app.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * X-Local-Token 预认证过滤器：挂在 Spring Security 链内（UsernamePasswordAuthenticationFilter 之前），
 * 让 Chrome 插件在无 Session Cookie 的情况下也能调用写接口。
 *
 * <p>背景：认证上线后，Spring Security 链先于组件注册的 LocalTokenFilter 执行，
 * 插件的 X-Local-Token 请求会在到达 LocalTokenFilter 前被 401 拦截。本过滤器把
 * 「令牌正确」翻译成 SecurityContext 里的已认证身份，插件与浏览器走同一套授权体系：
 * <ul>
 *   <li>无 X-Local-Token 头 → 直接放行（浏览器请求靠后续 Session 认证）；</li>
 *   <li>令牌正确 → 预认证通过，等同登录用户；</li>
 *   <li>令牌错误 → 401（防试探）。</li>
 * </ul>
 * 仅注册进启用认证的 SecurityFilterChain（authEnabled=false 时无安全链，不需要它）。
 */
public class LocalTokenAuthFilter extends OncePerRequestFilter {

    private final String localToken;

    public LocalTokenAuthFilter(String localToken) {
        this.localToken = localToken;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 只关心带令牌的请求；无令牌的一律交给后续 Session 认证
        return request.getHeader("X-Local-Token") == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (localToken != null && localToken.equals(request.getHeader("X-Local-Token"))) {
            var auth = new UsernamePasswordAuthenticationToken(
                    "extension", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
            SecurityContextHolder.getContext().setAuthentication(auth);
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"detail\":\"缺少或错误的 X-Local-Token\"}");
    }
}
