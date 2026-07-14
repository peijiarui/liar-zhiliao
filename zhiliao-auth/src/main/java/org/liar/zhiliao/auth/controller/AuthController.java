package org.liar.zhiliao.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.auth.entity.SysUser;
import org.liar.zhiliao.auth.record.SessionData;
import org.liar.zhiliao.auth.record.TokenPair;
import org.liar.zhiliao.auth.service.TokenService;
import org.liar.zhiliao.auth.service.UserService;
import org.liar.zhiliao.common.model.CurrentUser;
import org.liar.zhiliao.common.utils.UserContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证接口：登录、登出、获取当前用户、刷新 token。
 * 全部使用 Authorization: Bearer 头传递 access token。
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final TokenService tokenService;

    /**
     * POST /api/auth/login — 用户名密码登录，返回 access + refresh token
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> request) {
        try {
            String loginName = request.get("login_name");
            String password = request.get("password");

            SysUser user = userService.authenticate(loginName, password);
            CurrentUser currentUser = new CurrentUser(
                    user.getId(), user.getLoginName(), user.getName(), user.getDeptId());
            TokenPair pair = tokenService.issueToken(currentUser);

            log.info("登录成功: userId={}, loginName={}", user.getId(), user.getLoginName());
            return ResponseEntity.ok(pair);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /api/auth/logout — 吊销当前 access token 及关联 refresh token
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        String accessToken = extractBearer(request);
        if (accessToken != null) {
            tokenService.revoke(accessToken);
        }
        log.info("登出: accessToken={}", accessToken != null ? "revoked" : "no-token");
        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * GET /api/auth/me — 获取当前登录用户信息
     */
    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest request) {
        CurrentUser currentUser = UserContextHolder.get();
        if (currentUser == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }
        String accessToken = extractBearer(request);
        SessionData session = accessToken != null ? tokenService.getSession(accessToken) : null;
        long expiresAt = session != null ? session.expiresAt() : 0L;

        return ResponseEntity.ok(Map.of(
                "id", currentUser.id(),
                "loginName", currentUser.loginName(),
                "name", currentUser.name(),
                "deptId", currentUser.deptId(),
                "visibleDeptIds", currentUser.visibleDeptIds(),
                "expiresAt", expiresAt
        ));
    }

    /**
     * POST /api/auth/refresh — 用 refresh token 换新 access + refresh token
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody Map<String, String> request) {
        String refreshToken = request.get("refreshToken");
        if (refreshToken == null || refreshToken.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "refresh_token_missing"));
        }
        try {
            TokenPair pair = tokenService.refresh(refreshToken);
            return ResponseEntity.ok(pair);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
    }

    private String extractBearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
