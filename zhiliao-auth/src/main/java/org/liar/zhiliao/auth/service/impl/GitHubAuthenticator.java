package org.liar.zhiliao.auth.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.auth.service.OAuth2Authenticator;
import org.liar.zhiliao.auth.config.OAuth2Config;
import org.liar.zhiliao.auth.record.OAuth2UserInfo;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * GitHub OAuth2 认证器。
 * 使用标准 OAuth2 授权码流程，获取用户信息和邮箱。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GitHubAuthenticator implements OAuth2Authenticator {

    private final OAuth2Config config;
    private final RestTemplate restTemplate;

    @Override
    public String provider() {
        return "github";
    }

    @Override
    public OAuth2UserInfo authenticate(String code) {
        String accessToken = getAccessToken(code);
        Map<String, Object> userInfo = getUserInfo(accessToken);
        String email = getPrimaryEmail(accessToken);

        String providerUserId = String.valueOf(userInfo.get("id"));
        String name = (String) userInfo.getOrDefault("login", providerUserId);

        return new OAuth2UserInfo(providerUserId, email, name);
    }

    private String getAccessToken(String code) {
        log.info("======开始获取github access_token======");
        OAuth2Config.ProviderConfig github = config.getGithub();
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", github.getClientId());
        body.add("client_secret", github.getClientSecret());
        body.add("code", code);
        body.add("redirect_uri", github.getRedirectUri());

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "https://github.com/login/oauth/access_token",
                HttpMethod.POST,
                request,
                new ParameterizedTypeReference<>() {}
        );

        Map<String, Object> result = response.getBody();
        if (result == null || !result.containsKey("access_token")) {
            throw new IllegalStateException("GitHub access token request failed: " + result);
        }
        return (String) result.get("access_token");
    }

    private Map<String, Object> getUserInfo(String accessToken) {
        log.info("======开始获取github用户信息======");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> request = new HttpEntity<>(headers);
        // 使用 ParameterizedTypeReference 明确指定返回的泛型类型
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "https://api.github.com/user",
                HttpMethod.GET,
                request,
                new ParameterizedTypeReference<>() {});

        return response.getBody();
    }

    private String getPrimaryEmail(String accessToken) {
        try {
            log.info("======开始获取github用户邮箱信息======");
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            HttpEntity<Void> request = new HttpEntity<>(headers);
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    "https://api.github.com/user/emails", HttpMethod.GET, request, new ParameterizedTypeReference<>() {});
            List<Map<String, Object>> emails = response.getBody();
            if (emails != null) {
                for (Map<String, Object> email : emails) {
                    if (Boolean.TRUE.equals(email.get("primary"))) {
                        return (String) email.get("email");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get GitHub primary email: {}", e.getMessage());
        }
        return null;
    }
}
