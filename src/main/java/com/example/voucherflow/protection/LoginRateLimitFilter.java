package com.example.voucherflow.protection;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** 按客户端地址限制登录请求频率，在认证处理前返回准入结果。 */
public class LoginRateLimitFilter extends OncePerRequestFilter {
    private final RateLimitProperties config;
    private final RedisRateLimiter limiter;
    private final ObjectMapper json;

    public LoginRateLimitFilter(
            RateLimitProperties config, RedisRateLimiter limiter, ObjectMapper json) {
        this.config = config;
        this.limiter = limiter;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !config.enabled()
                || !"POST".equals(request.getMethod())
                || !"/api/auth/login".equals(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            limiter.acquire(
                    "vf:limit:login:" + request.getRemoteAddr(),
                    config.loginLimit(),
                    config.loginWindow(),
                    "login");
        } catch (AdmissionException ex) {
            // 此处尚未进入控制器，直接写出与接口层一致的 JSON 错误响应。
            response.setStatus(ex.status());
            response.setHeader("Retry-After", Long.toString(ex.retryAfterSeconds()));
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            json.writeValue(response.getOutputStream(), ex.response());
            return;
        }
        chain.doFilter(request, response);
    }
}
