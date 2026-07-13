package org.liar.zhiliao.auth.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.common.model.CurrentUser;
import org.liar.zhiliao.common.utils.JwtUtil;
import org.liar.zhiliao.common.utils.UserContextHolder;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class JwtFilter implements Filter {

    private final JwtUtil jwtUtil;

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) servletRequest;
        String path = request.getRequestURI();

        // Skip auth for login and OAuth2 endpoints
        if (path.equals("/api/auth/login") || path.startsWith("/oauth2/")) {
            chain.doFilter(servletRequest, servletResponse);
            return;
        }

        String authHeader = request.getHeader("Authorization");

        try {
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                if (jwtUtil.validateToken(token)) {
                    CurrentUser user = jwtUtil.parseToken(token);
                    UserContextHolder.set(user);
                    chain.doFilter(servletRequest, servletResponse);
                    return;
                }
            }
            ((HttpServletResponse) servletResponse).sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
        } finally {
            UserContextHolder.clear();
        }
    }
}
