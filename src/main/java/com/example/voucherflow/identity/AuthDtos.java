package com.example.voucherflow.identity;

import com.example.voucherflow.identity.model.Role;

/** 认证接口的数据结构，仅向客户端返回身份信息和 CSRF 凭证。 */
public final class AuthDtos {
    private AuthDtos() {}

    public record Me(String userId, String username, Role role) {}

    public record Csrf(String headerName, String parameterName, String token) {}
}
