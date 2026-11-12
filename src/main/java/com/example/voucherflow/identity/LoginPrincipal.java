package com.example.voucherflow.identity;

import com.example.voucherflow.identity.model.Role;
import java.io.Serial;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** 可保存到会话的登录身份；密码摘要仅参与认证，不随身份序列化。 */
public final class LoginPrincipal implements UserDetails, CredentialsContainer {
    @Serial
    private static final long serialVersionUID = 1L;

    private final long id;
    private final String username;
    private final Role role;
    // 会话可持久化到数据库，认证所需的密码摘要不进入序列化内容。
    private transient String passwordHash;

    public LoginPrincipal(long id, String username, Role role, String passwordHash) {
        this.id = id;
        this.username = username;
        this.role = role;
        this.passwordHash = passwordHash;
    }

    public long id() { return id; }
    public Role role() { return role; }
    @Override public String getUsername() { return username; }
    @Override public String getPassword() { return passwordHash; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
    @Override public void eraseCredentials() { passwordHash = null; }
}
