package com.example.voucherflow.identity;

import com.example.voucherflow.identity.mapper.UserMapper;
import com.example.voucherflow.identity.model.Role;
import com.example.voucherflow.identity.model.UserRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** 在本地或测试环境按配置初始化演示账号，保留已存在账号的密码。 */
@Component
@Profile({"local", "test"})
@ConditionalOnProperty(name = "voucherflow.demo-users.enabled", havingValue = "true")
public class DemoUserInitializer implements ApplicationRunner {
    private final UserMapper users;
    private final PasswordEncoder passwords;
    private final String adminPassword;
    private final String userPassword;

    public DemoUserInitializer(UserMapper users, PasswordEncoder passwords,
            @Value("${voucherflow.demo-users.admin-password}") String adminPassword,
            @Value("${voucherflow.demo-users.user-password}") String userPassword) {
        this.users = users;
        this.passwords = passwords;
        this.adminPassword = adminPassword;
        this.userPassword = userPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (adminPassword.isBlank() || userPassword.isBlank()) {
            throw new IllegalStateException("Demo account passwords must be configured");
        }
        ensureUser("admin-demo", Role.ADMIN, adminPassword);
        ensureUser("user-demo", Role.USER, userPassword);
    }

    private void ensureUser(String username, Role role, String rawPassword) {
        var existing = users.findByUsername(username);
        if (existing.isPresent()) {
            verifyRole(existing.get(), role);
            return;
        }
        var user = new UserRow();
        user.setUsername(username);
        user.setRole(role);
        user.setPasswordHash(passwords.encode(rawPassword));
        try {
            users.insert(user);
        } catch (DuplicateKeyException exception) {
            // 多个实例同时启动时，唯一约束决定由哪一个实例完成账号初始化。
            verifyRole(users.findByUsername(username).orElseThrow(() -> exception), role);
        }
    }

    private void verifyRole(UserRow user, Role expected) {
        if (user.getRole() != expected) {
            throw new IllegalStateException("Unexpected demo account role: " + user.getUsername());
        }
    }
}
