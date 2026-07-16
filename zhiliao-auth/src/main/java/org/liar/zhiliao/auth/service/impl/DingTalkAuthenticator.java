package org.liar.zhiliao.auth.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.liar.zhiliao.auth.config.OAuth2Config;
import org.liar.zhiliao.auth.record.OAuth2UserInfo;
import org.liar.zhiliao.auth.record.resp.DingTalkTokenResp;
import org.liar.zhiliao.auth.record.resp.DingTalkUserInfoResp;
import org.liar.zhiliao.auth.service.OAuth2Authenticator;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.liar.zhiliao.auth.enums.OAuth2ProviderEnum.DINGTALK;

/**
 * 钉钉扫码 OAuth2 认证器。
 * 使用钉钉 OAuth2 授权码流程：authCode → userAccessToken → 用户信息 → 服务端 API 补全邮箱。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DingTalkAuthenticator implements OAuth2Authenticator {

    private final OAuth2Config config;
    private final RestTemplate restTemplate;

    @Override
    public String provider() {
        return DINGTALK.getProvider();
    }

    @Override
    public OAuth2UserInfo authenticate(String code) {
        String accessToken = getUserAccessToken(code);
        DingTalkUserInfoResp userInfo = getUserInfo(accessToken);
        log.info("DingTalk userInfo: {}", userInfo);

        return OAuth2UserInfo.of(userInfo);
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
        ResponseEntity<DingTalkTokenResp> response = restTemplate.postForEntity(
                DINGTALK.getAccessTokenUrl(), request, DingTalkTokenResp.class);

        DingTalkTokenResp tokenResp = response.getBody();
        if (tokenResp == null || StringUtils.isEmpty(tokenResp.accessToken())) {
            throw new IllegalStateException("DingTalk access token request failed: " + response);
        }
        return tokenResp.accessToken();
    }

    private DingTalkUserInfoResp getUserInfo(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-acs-dingtalk-access-token", accessToken);
        HttpEntity<Void> request = new HttpEntity<>(headers);
        ResponseEntity<DingTalkUserInfoResp> response = restTemplate.exchange(
                DINGTALK.getUserInfoUrl(), HttpMethod.GET, request, DingTalkUserInfoResp.class);
        if (response.getBody() == null) {
            throw new IllegalStateException("DingTalk user info request failed: " + response);
        }
        return response.getBody();
    }
}
