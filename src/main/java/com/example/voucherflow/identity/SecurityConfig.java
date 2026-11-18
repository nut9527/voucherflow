package com.example.voucherflow.identity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

import com.example.voucherflow.protection.LoginRateLimitFilter;

/** 配置会话认证、角色授权、CSRF 校验及登录限流的执行顺序。 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {
        @Bean
        PasswordEncoder passwordEncoder() {
                return new BCryptPasswordEncoder();
        }

        @Bean
        DaoAuthenticationProvider authenticationProvider(
                        DatabaseUserDetailsService users, PasswordEncoder encoder) {
                var provider = new DaoAuthenticationProvider(users);
                provider.setPasswordEncoder(encoder);
                return provider;
        }

        @Bean
        SecurityFilterChain filterChain(
                        HttpSecurity http,
                        JsonSecurityHandlers handlers,
                        DaoAuthenticationProvider provider,
                        LoginRateLimitFilter loginRateLimit)
                        throws Exception {
                return http.authenticationProvider(provider)
                                // 先限流，再执行密码认证，减少登录高频请求占用认证资源。
                                .addFilterBefore(loginRateLimit, UsernamePasswordAuthenticationFilter.class)
                                .authorizeHttpRequests(
                                                authorize -> authorize
                                                                .requestMatchers(
                                                                                HttpMethod.GET,
                                                                                "/api/auth/csrf",
                                                                                "/api/campaigns",
                                                                                "/api/campaigns/*",
                                                                                "/actuator/health/liveness",
                                                                                "/actuator/health/readiness")
                                                                .permitAll()
                                                                .requestMatchers(HttpMethod.POST, "/api/auth/login")
                                                                .permitAll()
                                                                // 供应商回调由业务层校验签名，不依赖浏览器登录会话。
                                                                .requestMatchers(HttpMethod.POST,
                                                                                "/api/supplier/callbacks")
                                                                .permitAll()
                                                                .requestMatchers("/api/auth/me")
                                                                .authenticated()
                                                                .requestMatchers(
                                                                                "/api/admin/**",
                                                                                "/actuator/health",
                                                                                "/actuator/metrics/**")
                                                                .hasRole("ADMIN")
                                                                .requestMatchers(HttpMethod.POST,
                                                                                "/api/vouchers/*/redeem")
                                                                .hasRole("ADMIN")
                                                                .requestMatchers(HttpMethod.POST,
                                                                                "/api/campaigns/*/claim")
                                                                .hasRole("USER")
                                                                .requestMatchers("/api/me/**")
                                                                .hasRole("USER")
                                                                .anyRequest()
                                                                .denyAll())
                                // 回调使用独立签名协议；浏览器写请求继续使用会话中的 CSRF 凭证。
                                .csrf(
                                                csrf -> csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository())
                                                                .ignoringRequestMatchers("/api/supplier/callbacks"))
                                .formLogin(
                                                login -> login.loginProcessingUrl("/api/auth/login")
                                                                .successHandler(handlers)
                                                                .failureHandler(handlers))
                                .logout(
                                                logout -> logout.logoutUrl("/api/auth/logout")
                                                                .logoutSuccessHandler(handlers))
                                .exceptionHandling(
                                                exceptions -> exceptions
                                                                .authenticationEntryPoint(handlers)
                                                                .accessDeniedHandler(handlers))
                                .requestCache(cache -> cache.disable())
                                .build();
        }
}
