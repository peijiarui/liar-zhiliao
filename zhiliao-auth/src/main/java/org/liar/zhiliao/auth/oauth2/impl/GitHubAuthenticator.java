package org.liar.zhiliao.auth.oauth2.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.auth.oauth2.OAuth2Authenticator;
import org.liar.zhiliao.auth.oauth2.OAuth2Config;
import org.liar.zhiliao.auth.oauth2.OAuth2UserInfo;
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
        OAuth2Config.ProviderConfig github = config.getGithub();
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", github.getClientId());
        body.add("client_secret", github.getClientSecret());
        body.add("code", code);
        body.add("redirect_uri", github.getRedirectUri());

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "https://github.com/login/oauth/access_token", request, Map.class);

        Map<String, Object> result = response.getBody();
        if (result == null || !result.containsKey("access_token")) {
            throw new IllegalStateException("GitHub access token request failed: " + result);
        }
        return (String) result.get("access_token");
    }

    private Map<String, Object> getUserInfo(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> request = new HttpEntity<>(headers);
        ResponseEntity<Map> response = restTemplate.exchange(
                "https://api.github.com/user", HttpMethod.GET, request, Map.class);
        return response.getBody();
    }

    @SuppressWarnings("unchecked")
    private String getPrimaryEmail(String accessToken) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            HttpEntity<Void> request = new HttpEntity<>(headers);
            ResponseEntity<List> response = restTemplate.exchange(
                    "https://api.github.com/user/emails", HttpMethod.GET, request, List.class);
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
