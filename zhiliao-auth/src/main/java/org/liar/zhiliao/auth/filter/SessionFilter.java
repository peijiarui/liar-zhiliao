package org.liar.zhiliao.auth.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.auth.record.SessionData;
import org.liar.zhiliao.auth.service.TokenService;
import org.liar.zhiliao.common.model.CurrentUser;
import org.liar.zhiliao.common.utils.UserContextHolder;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 会话过滤器：从 Authorization: Bearer 头提取 access token，
 * 查 Redis 校验有效性，注入 UserContextHolder。
 * 替代旧的 JwtFilter。
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class SessionFilter implements Filter {

    private final TokenService tokenService;

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        String path = request.getRequestURI();

        // 跳过鉴权的路径：login、refresh、OAuth2 授权启动与回调
        if (path.equals("/api/auth/login")
                || path.equals("/api/auth/refresh")
                || path.startsWith("/oauth2/")) {
            chain.doFilter(req, res);
            return;
        }

        String token = extractBearer(request);
        if (token == null) {
            reject(response, "token_missing");
            return;
        }

        SessionData session = tokenService.getSession(token);
        if (session == null) {
            reject(response, "token_invalid");
            return;
        }

        try {
            CurrentUser user = new CurrentUser(
                    session.userId(), session.loginName(), session.name(),
                    session.deptId(), session.visibleDeptIds());
            UserContextHolder.set(user);
            chain.doFilter(req, res);
        } finally {
            UserContextHolder.clear();
        }
    }

    private String extractBearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }

    private void reject(HttpServletResponse response, String errorCode) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + errorCode + "\"}");
    }
}
