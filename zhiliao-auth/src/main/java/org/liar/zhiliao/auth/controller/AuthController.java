package org.liar.zhiliao.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.auth.entity.SysUser;
import org.liar.zhiliao.auth.record.SessionData;
import org.liar.zhiliao.auth.record.TokenPair;
import org.liar.zhiliao.auth.vo.request.LoginRequest;
import org.liar.zhiliao.auth.vo.request.RefreshTokenRequest;
import org.liar.zhiliao.auth.vo.response.CurrentUserResponse;
import org.liar.zhiliao.auth.service.TokenService;
import org.liar.zhiliao.auth.service.UserService;
import org.liar.zhiliao.common.exception.AuthException;
import org.liar.zhiliao.common.model.CurrentUser;
import org.liar.zhiliao.common.result.ResponseResult;
import org.liar.zhiliao.common.utils.UserContextHolder;
import org.springframework.web.bind.annotation.*;

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

    @PostMapping("/login")
    public TokenPair login(@RequestBody LoginRequest request) {
        try {
            SysUser user = userService.authenticate(request.loginName(), request.password());
            CurrentUser currentUser = new CurrentUser(
                    user.getId(), user.getLoginName(), user.getName(), user.getDeptId());
            TokenPair pair = tokenService.issueToken(currentUser);

            log.info("登录成功: userId={}, loginName={}", user.getId(), user.getLoginName());
            return pair;
        } catch (IllegalArgumentException e) {
            throw new AuthException(401, e.getMessage());
        }
    }

    @PostMapping("/logout")
    public ResponseResult<Void> logout(HttpServletRequest request) {
        String accessToken = extractBearer(request);
        if (accessToken != null) {
            tokenService.revoke(accessToken);
        }
        log.info("登出: accessToken={}", accessToken != null ? "revoked" : "no-token");
        return ResponseResult.ok();
    }

    @GetMapping("/me")
    public CurrentUserResponse me(HttpServletRequest request) {
        CurrentUser currentUser = UserContextHolder.get();
        if (currentUser == null) {
            throw new AuthException(401, "Not authenticated");
        }
        String accessToken = extractBearer(request);
        SessionData session = accessToken != null ? tokenService.getSession(accessToken) : null;
        long expiresAt = session != null ? session.expiresAt() : 0L;

        return CurrentUserResponse.of(currentUser, expiresAt);
    }

    @PostMapping("/refresh")
    public TokenPair refresh(@RequestBody RefreshTokenRequest request) {
        if (request.refreshToken() == null || request.refreshToken().isBlank()) {
            throw new AuthException(401, "refresh_token_missing");
        }
        try {
            return tokenService.refresh(request.refreshToken());
        } catch (IllegalStateException e) {
            throw new AuthException(401, e.getMessage());
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
