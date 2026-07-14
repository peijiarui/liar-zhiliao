package org.liar.zhiliao.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.auth.oauth2.OAuth2Authenticator;
import org.liar.zhiliao.auth.oauth2.OAuth2Config;
import org.liar.zhiliao.auth.oauth2.OAuth2UserInfo;
import org.liar.zhiliao.auth.service.DeptPermissionService;
import org.liar.zhiliao.auth.service.UserLinkService;
import org.liar.zhiliao.auth.entity.SysUser;
import org.liar.zhiliao.common.model.CurrentUser;
import org.liar.zhiliao.common.utils.JwtUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OAuth2 登录控制器。
 * 统一处理 GitHub 和钉钉的授权回调，通过 provider 路径变量路由到对应认证器。
 */
@Slf4j
@RestController
@RequestMapping("/oauth2")
@RequiredArgsConstructor
public class OAuth2Controller {

    private final List<OAuth2Authenticator> authenticators;
    private final UserLinkService userLinkService;
    private final DeptPermissionService deptPermissionService;
    private final JwtUtil jwtUtil;
    private final OAuth2Config config;

    /** GET /oauth2/github — 302 跳转 GitHub 授权页（含 state CSRF 保护） */
    @GetMapping("/github")
    public void githubAuthorize(HttpServletRequest request, HttpServletResponse response) throws IOException {
        OAuth2Config.ProviderConfig github = config.getGithub();
        String state = UUID.randomUUID().toString();
        request.getSession(true).setAttribute("oauth_state", state);
        String url = String.format(
                "https://github.com/login/oauth/authorize?client_id=%s&redirect_uri=%s&scope=user:email&state=%s",
                github.getClientId(), github.getRedirectUri(), state);
        response.sendRedirect(url);
    }

    /** GET /oauth2/{provider}/callback — OAuth2 统一回调入口 */
    @GetMapping("/{provider}/callback")
    public void callback(@PathVariable String provider, @RequestParam("code") String code,
                         @RequestParam(value = "state", required = false) String state,
                         HttpServletRequest request, HttpServletResponse response) throws IOException {
        // 仅对 GitHub OAuth 验证 state 参数（CSRF 保护）
        if ("github".equals(provider)) {
            HttpSession session = request.getSession(false);
            String savedState = (session != null) ? (String) session.getAttribute("oauth_state") : null;
            if (savedState == null || !savedState.equals(state)) {
                log.warn("OAuth state mismatch: provider={}, expected={}, actual={}", provider, savedState, state);
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid OAuth state parameter");
                return;
            }
            session.removeAttribute("oauth_state");
        }

        OAuth2Authenticator authenticator = authenticators.stream()
                .filter(a -> a.provider().equals(provider))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown OAuth provider: " + provider));

        OAuth2UserInfo userInfo = authenticator.authenticate(code);
        SysUser user = userLinkService.linkOrCreate(userInfo, provider);

        List<Long> visibleDeptIds = deptPermissionService.getVisibleDeptIds(user.getDeptId());
        CurrentUser currentUser = CurrentUser.of(user.getId(), user.getUsername(), user.getDeptId(), visibleDeptIds);
        String token = jwtUtil.generateToken(currentUser);

        log.info("OAuth login success: provider={}, userId={}, username={}", provider, user.getId(), user.getUsername());

        // 302 重定向到首页，JWT 通过 Cookie 传递
        Cookie jwtCookie = new Cookie("zhiliao_token", token);
        jwtCookie.setPath("/");
        jwtCookie.setHttpOnly(true);
        jwtCookie.setMaxAge(60 * 60); // 1 hour
        response.addCookie(jwtCookie);
        response.sendRedirect("http://localhost:5173"); // 跳转到前端
    }

    /** GET /oauth2/dingtalk/authorize — 返回钉钉扫码授权 URL（前端生成二维码用） */
    @GetMapping("/dingtalk/authorize")
    public ResponseEntity<Map<String, String>> dingtalkAuthorizeUrl() {
        OAuth2Config.ProviderConfig dingtalk = config.getDingtalk();
        String url = String.format(
                "https://login.dingtalk.com/oauth2/auth?redirect_uri=%s&response_type=code&client_id=%s&scope=openid&prompt=consent",
                dingtalk.getRedirectUri(), dingtalk.getClientId());
        return ResponseEntity.ok(Map.of("authUrl", url));
    }
}
