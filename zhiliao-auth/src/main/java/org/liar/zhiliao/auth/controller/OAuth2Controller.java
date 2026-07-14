package org.liar.zhiliao.auth.controller;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.auth.config.AuthProperties;
import org.liar.zhiliao.auth.config.OAuth2Config;
import org.liar.zhiliao.auth.entity.SysUser;
import org.liar.zhiliao.auth.record.OAuth2UserInfo;
import org.liar.zhiliao.auth.record.TokenPair;
import org.liar.zhiliao.auth.service.DeptPermissionService;
import org.liar.zhiliao.auth.service.OAuth2Authenticator;
import org.liar.zhiliao.auth.service.TokenService;
import org.liar.zhiliao.auth.service.UserLinkService;
import org.liar.zhiliao.common.model.CurrentUser;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * OAuth2 登录控制器。
 * 统一处理 GitHub 和钉钉的授权回调，通过 provider 路径变量路由到对应认证器。
 * state 存 Redis（TTL 5min），回调时校验后立即删除，防 CSRF。
 * 回调成功后 302 重定向到前端 /oauth/callback，token 通过 URL fragment 传递。
 */
@Slf4j
@RestController
@RequestMapping("/oauth2")
@RequiredArgsConstructor
public class OAuth2Controller {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final List<OAuth2Authenticator> authenticators;
    private final UserLinkService userLinkService;
    private final DeptPermissionService deptPermissionService;
    private final TokenService tokenService;
    private final OAuth2Config config;
    private final AuthProperties authProps;
    private final StringRedisTemplate redis;

    /**
     * GET /oauth2/github — 302 跳转 GitHub 授权页（state 写入 Redis）
     */
    @GetMapping("/github")
    public void githubAuthorize(HttpServletResponse response) throws IOException {
        OAuth2Config.ProviderConfig github = config.getGithub();
        String state = generateState();
        redis.opsForValue().set(stateKey(state), "github",
                Duration.ofSeconds(authProps.getOauthStateTtlSeconds()));

        String url = String.format(
                "https://github.com/login/oauth/authorize?client_id=%s&redirect_uri=%s&scope=user:email&state=%s",
                github.getClientId(), github.getRedirectUri(), state);
        response.sendRedirect(url);
    }

    /**
     * GET /oauth2/dingtalk/authorize — 返回带 state 的钉钉扫码 URL
     */
    @GetMapping("/dingtalk/authorize")
    public ResponseEntity<Map<String, String>> dingtalkAuthorizeUrl() {
        OAuth2Config.ProviderConfig dingtalk = config.getDingtalk();
        String state = generateState();
        redis.opsForValue().set(stateKey(state), "dingtalk",
                Duration.ofSeconds(authProps.getOauthStateTtlSeconds()));

        String url = String.format(
                "https://login.dingtalk.com/oauth2/auth?redirect_uri=%s&response_type=code&client_id=%s&scope=openid&prompt=consent&state=%s",
                dingtalk.getRedirectUri(), dingtalk.getClientId(), state);
        return ResponseEntity.ok(Map.of("authUrl", url));
    }

    /**
     * GET /oauth2/{provider}/callback — OAuth2 统一回调入口，返回 HTML 注入 token
     */
    @GetMapping("/{provider}/callback")
    public void callback(@PathVariable String provider,
                         @RequestParam("code") String code,
                         @RequestParam(value = "state", required = false) String state,
                         HttpServletResponse response) throws IOException {
        // 校验 state（GitHub 和钉钉都强制校验）
        String storedProvider = redis.opsForValue().get(stateKey(state));
        if (storedProvider == null || !storedProvider.equals(provider)) {
            log.warn("OAuth state mismatch: provider={}, state={}", provider, state);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid OAuth state");
            return;
        }
        redis.delete(stateKey(state));  // 立即删除防重放

        OAuth2Authenticator authenticator = authenticators.stream()
                .filter(a -> a.provider().equals(provider))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown OAuth provider: " + provider));

        OAuth2UserInfo userInfo = authenticator.authenticate(code);
        SysUser user = userLinkService.linkOrCreate(userInfo, provider);    //关联用户或创建

        List<Long> visibleDeptIds = deptPermissionService.getVisibleDeptIds(user.getDeptId());
        CurrentUser currentUser = CurrentUser.of(
                user.getId(), user.getLoginName(), user.getName(), user.getDeptId(), visibleDeptIds);
        TokenPair pair = tokenService.issueToken(currentUser);

        log.info("OAuth login success: provider={}, userId={}, loginName={}",
                provider, user.getId(), user.getLoginName());

        // 302 重定向到前端 /oauth/callback，token 通过 URL fragment 传递
        // fragment (# 后) 不会发到服务器，也不会被代理/日志记录，比 query 更安全
        String redirectUrl = authProps.getWebFrontendBaseUrl()
                + "/#/oauth/callback"
                + "?accessToken=" + URLEncoder.encode(pair.accessToken(), StandardCharsets.UTF_8)
                + "&refreshToken=" + URLEncoder.encode(pair.refreshToken(), StandardCharsets.UTF_8)
                + "&loginName=" + URLEncoder.encode(pair.user().loginName(), StandardCharsets.UTF_8)
                + "&name=" + URLEncoder.encode(
                        pair.user().name() != null ? pair.user().name() : "", StandardCharsets.UTF_8);
        response.sendRedirect(redirectUrl);
    }

    private String generateState() {
        byte[] bytes = new byte[16];
        SECURE_RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }

    private String stateKey(String state) {
        return "auth:oauth:state:" + authProps.getAppId() + ":" + state;
    }
}
