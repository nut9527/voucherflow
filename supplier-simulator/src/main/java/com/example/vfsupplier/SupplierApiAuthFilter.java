package com.example.vfsupplier;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 校验供应商接口的 Bearer 凭证，包括发放、查询与故障控制接口。 */
@Component
public class SupplierApiAuthFilter extends OncePerRequestFilter {
    private final SupplierProperties properties;

    public SupplierApiAuthFilter(SupplierProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            jakarta.servlet.http.HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response,
            jakarta.servlet.FilterChain chain)
            throws jakarta.servlet.ServletException, java.io.IOException {
        if (request.getRequestURI().startsWith("/supplier/")) {
            String token = request.getHeader("Authorization");
            if (token == null
                    || !MessageDigest.isEqual(
                            token.getBytes(StandardCharsets.UTF_8),
                            ("Bearer " + properties.getApiToken())
                                    .getBytes(StandardCharsets.UTF_8))) {
                response.setStatus(401);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
