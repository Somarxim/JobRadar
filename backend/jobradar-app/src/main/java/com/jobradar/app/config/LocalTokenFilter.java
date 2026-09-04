package com.jobradar.app.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 本地令牌过滤器（api-design.md §3 鉴权）。
 *
 * <p>防的不是"黑客"而是 CSRF 场景：恶意网页可以借浏览器向 127.0.0.1:8080 发起
 * 简单跨域 POST（CORS 只拦读不拦写）。规则：
 * <ul>
 *   <li>GET/HEAD/OPTIONS（只读）一律放行；</li>
 *   <li>写请求带受信 Origin（vite dev server）→ 放行（浏览器保证 Origin 不可伪造）；</li>
 *   <li>其余写请求（Chrome 插件、curl、未知来源网页）→ 必须带 X-Local-Token。</li>
 * </ul>
 * OncePerRequestFilter：保证单次请求只执行一次（容器转发/async 分派不会重复触发），
 * 是 Spring 写过滤器的标准基类。
 */
@Component
public class LocalTokenFilter extends OncePerRequestFilter {

    private final String localToken;
    private final List<String> webOrigins;

    public LocalTokenFilter(JobRadarProperties properties) {
        this.localToken = properties.security() == null ? null : properties.security().localToken();
        this.webOrigins = properties.cors() == null ? List.of() : properties.cors().allowedOrigins();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        return !path.startsWith("/api/")
                || method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (origin != null && webOrigins.contains(origin)) {
            chain.doFilter(request, response);
            return;
        }
        if (localToken != null && localToken.equals(request.getHeader("X-Local-Token"))) {
            chain.doFilter(request, response);
            return;
        }
        // 与全局异常格式一致的 {"detail": ...}
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"detail\":\"缺少或错误的 X-Local-Token（本地 API 写操作鉴权）\"}");
    }
}
