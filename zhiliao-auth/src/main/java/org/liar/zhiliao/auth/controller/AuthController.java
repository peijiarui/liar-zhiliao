package org.liar.zhiliao.auth.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.auth.entity.SysUser;
import org.liar.zhiliao.auth.service.UserService;
import org.liar.zhiliao.common.model.CurrentUser;
import org.liar.zhiliao.common.utils.JwtUtil;
import org.liar.zhiliao.common.utils.UserContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final JwtUtil jwtUtil;

    private static final String TOKEN_COOKIE_NAME = "zhiliao_token";

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> request) {
        try {
            String username = request.get("username");
            String password = request.get("password");

            SysUser user = userService.authenticate(username, password);
            CurrentUser currentUser = new CurrentUser(user.getId(), user.getUsername(), user.getDeptId());
            String token = jwtUtil.generateToken(currentUser);

            log.info("===== 登录成功 =====\nToken: {}", token);

            return ResponseEntity.ok(Map.of("token", token));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletResponse response) {
        log.info("===== 登出 =====");

        // 清除 zhiliao_token cookie（OAuth 登录场景下 JS 无法清除 HttpOnly cookie，必须由后端清）
        Cookie expired = new Cookie(TOKEN_COOKIE_NAME, "");
        expired.setPath("/");
        expired.setHttpOnly(true);
        expired.setMaxAge(0);
        response.addCookie(expired);

        return ResponseEntity.ok(Map.of("success", true));
    }

    /** GET /api/auth/me — 获取当前登录用户信息（从 cookie 或 Bearer header 恢复 session） */
    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest request) {
        CurrentUser currentUser = UserContextHolder.get();
        if (currentUser == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }
        // 从当前请求中提取 token（优先 Bearer header，回退到 cookie）
        String token = extractToken(request);
        return ResponseEntity.ok(Map.of(
                "id", currentUser.id(),
                "username", currentUser.username(),
                "deptId", currentUser.deptId(),
                "visibleDeptIds", currentUser.visibleDeptIds(),
                "token", token != null ? token : ""
        ));
    }

    /** 从请求中提取 JWT（优先 Bearer header，回退到 cookie） */
    private String extractToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if ("zhiliao_token".equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        return null;
    }
}
