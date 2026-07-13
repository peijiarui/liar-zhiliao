package org.liar.zhiliao.auth.oauth2.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.auth.oauth2.OAuth2Authenticator;
import org.liar.zhiliao.auth.oauth2.OAuth2Config;
import org.liar.zhiliao.auth.oauth2.OAuth2UserInfo;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 钉钉扫码 OAuth2 认证器。
 * 使用钉钉 OAuth2 授权码流程：authCode → userAccessToken → 用户信息。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DingTalkAuthenticator implements OAuth2Authenticator {

    private final OAuth2Config config;
    private final RestTemplate restTemplate;

    @Override
    public String provider() {
        return "dingtalk";
    }

    @Override
    public OAuth2UserInfo authenticate(String code) {
        String accessToken = getUserAccessToken(code);
        Map<String, Object> userInfo = getUserInfo(accessToken);

        String unionId = (String) userInfo.get("unionId");
        String nick = (String) userInfo.getOrDefault("nick", unionId);
        String email = (String) userInfo.get("email");

        return new OAuth2UserInfo(unionId, email, nick);
    }

    private String getUserAccessToken(String code) {
        OAuth2Config.ProviderConfig dingtalk = config.getDingtalk();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, String> body = Map.of(
                "clientId", dingtalk.getClientId(),
                "clientSecret", dingtalk.getClientSecret(),
                "code", code,
                "grantType", "authorization_code"
        );

        HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "https://api.dingtalk.com/v1.0/oauth2/userAccessToken", request, Map.class);

        Map<String, Object> result = response.getBody();
        if (result == null || !result.containsKey("accessToken")) {
            throw new IllegalStateException("DingTalk access token request failed: " + result);
        }
        return (String) result.get("accessToken");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getUserInfo(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-acs-dingtalk-access-token", accessToken);
        HttpEntity<Void> request = new HttpEntity<>(headers);
        ResponseEntity<Map> response = restTemplate.exchange(
                "https://api.dingtalk.com/v1.0/contact/users/me", HttpMethod.GET, request, Map.class);
        return response.getBody();
    }
}
