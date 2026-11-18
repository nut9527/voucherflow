package com.example.voucherflow.identity;

import com.example.voucherflow.common.ApiResponse;
import com.example.voucherflow.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 将登录、退出、未认证和权限拒绝统一输出为 JSON 响应。 */
@Component
public class JsonSecurityHandlers
        implements AuthenticationSuccessHandler,
                AuthenticationFailureHandler,
                AuthenticationEntryPoint,
                AccessDeniedHandler,
                LogoutSuccessHandler {
    private final ObjectMapper json;
    private final CurrentUser currentUser;

    public JsonSecurityHandlers(ObjectMapper json, CurrentUser currentUser) {
        this.json = json;
        this.currentUser = currentUser;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        writeJson(response, 200, ApiResponse.ok(currentUser.me()));
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception)
            throws IOException {
        writeJson(response, 401, error(ErrorCode.BAD_CREDENTIALS));
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception)
            throws IOException {
        writeJson(response, 401, error(ErrorCode.UNAUTHENTICATED));
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception)
            throws IOException {
        writeJson(
                response,
                403,
                error(
                        exception instanceof CsrfException
                                ? ErrorCode.CSRF_INVALID
                                : ErrorCode.FORBIDDEN));
    }

    @Override
    public void onLogoutSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        writeJson(response, 200, ApiResponse.ok(null));
    }

    private ApiResponse<Void> error(ErrorCode code) {
        return ApiResponse.error(code.name(), code.defaultMessage());
    }

    private void writeJson(HttpServletResponse response, int status, Object body)
            throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        json.writeValue(response.getOutputStream(), body);
    }
}
