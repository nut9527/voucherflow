package com.example.voucherflow.identity;

import com.example.voucherflow.common.ApiResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供 CSRF 凭证和当前登录身份查询，登录与退出由安全过滤器处理。 */
@RestController
public class AuthController {
    private final CurrentUser currentUser;

    public AuthController(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    /** 返回当前会话的 CSRF 凭证，供客户端后续提交写请求。 */
    @GetMapping("/api/auth/csrf")
    public ApiResponse<AuthDtos.Csrf> csrf(CsrfToken token) {
        return ApiResponse.ok(
                new AuthDtos.Csrf(
                        token.getHeaderName(), token.getParameterName(), token.getToken()));
    }

    @GetMapping("/api/auth/me")
    public ApiResponse<AuthDtos.Me> me() {
        return ApiResponse.ok(currentUser.me());
    }
}
