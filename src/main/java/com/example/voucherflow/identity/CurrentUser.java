package com.example.voucherflow.identity;

import com.example.voucherflow.common.BusinessException;
import com.example.voucherflow.common.ErrorCode;
import com.example.voucherflow.identity.model.Role;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** 从安全上下文读取登录身份，并按业务入口要求检查用户角色。 */
@Component
public class CurrentUser {
    public LoginPrincipal requirePrincipal() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof LoginPrincipal principal)) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        return principal;
    }

    /** 普通用户业务必须使用 USER 身份，管理员身份不能代替用户领取权益。 */
    public long requireUserId() {
        return requireRole(Role.USER).id();
    }

    public long requireAdminId() {
        return requireRole(Role.ADMIN).id();
    }

    public AuthDtos.Me me() {
        var principal = requirePrincipal();
        return new AuthDtos.Me(
                Long.toString(principal.id()), principal.getUsername(), principal.role());
    }

    private LoginPrincipal requireRole(Role role) {
        var principal = requirePrincipal();
        if (principal.role() != role) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return principal;
    }
}
