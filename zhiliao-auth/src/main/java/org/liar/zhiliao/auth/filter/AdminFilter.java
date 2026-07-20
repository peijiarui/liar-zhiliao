package org.liar.zhiliao.auth.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.common.model.CurrentUser;
import org.liar.zhiliao.common.utils.UserContextHolder;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 管理员权限过滤器。
 * 拦截 /api/admin/* 路径，校验当前用户 role 是否为 ADMIN。
 * 在 SessionFilter（@Order(1)）之后执行，因 SessionFilter 已设置 UserContextHolder。
 *
 * <p>后续改造：迁移到 RBAC 时，只需将内部实现从 user.role() 枚举判断改为
 * 查 sys_role_permission 表，Filter 接口不变。</p>
 */
@Slf4j
@Component
@Order(2)
public class AdminFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        String path = req.getRequestURI();

        if (path.startsWith("/api/admin/")) {
            CurrentUser user = UserContextHolder.get();
            if (user == null || !"ADMIN".equals(user.role())) {
                HttpServletResponse resp = (HttpServletResponse) response;
                resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
                resp.setContentType("application/json;charset=UTF-8");
                resp.getWriter().write("{\"error\":\"forbidden\",\"message\":\"需要管理员权限\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
