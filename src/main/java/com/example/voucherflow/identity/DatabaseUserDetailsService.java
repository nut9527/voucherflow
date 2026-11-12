package com.example.voucherflow.identity;

import com.example.voucherflow.identity.mapper.UserMapper;
import java.util.Locale;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** 从数据库加载账号，将持久化用户转换为安全框架使用的登录身份。 */
@Service
public class DatabaseUserDetailsService implements UserDetailsService {
    private final UserMapper users;

    public DatabaseUserDetailsService(UserMapper users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        // 统一账号的空白与大小写处理，与数据库保存的规范化用户名一致。
        var user = users.findByUsername(username.trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
        return new LoginPrincipal(user.getId(), user.getUsername(), user.getRole(), user.getPasswordHash());
    }
}
