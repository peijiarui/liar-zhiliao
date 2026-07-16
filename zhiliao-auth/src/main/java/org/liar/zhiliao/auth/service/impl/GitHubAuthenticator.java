package org.liar.zhiliao.auth.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.liar.zhiliao.auth.config.OAuth2Config;
import org.liar.zhiliao.auth.record.OAuth2UserInfo;
import org.liar.zhiliao.auth.record.resp.GithubEmailResp;
import org.liar.zhiliao.auth.record.resp.GithubTokenResp;
import org.liar.zhiliao.auth.record.resp.GithubUserInfoResp;
import org.liar.zhiliao.auth.service.OAuth2Authenticator;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.liar.zhiliao.auth.enums.OAuth2ProviderEnum.GITHUB;

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

    /**
     * GitHub 登录提供商名称。
     */
    @Override
    public String provider() {
        return GITHUB.getProvider();
    }

    /**
     * 认证 GitHub 用户。
     *
     * @param code GitHub 授权码
     * @return GitHub 用户信息
     */
    @Override
    public OAuth2UserInfo authenticate(String code) {
        String accessToken = getAccessToken(code);
        GithubUserInfoResp userInfo = getUserInfo(accessToken);
        String email = getPrimaryEmail(accessToken);

        return OAuth2UserInfo.of(userInfo, email);
    }

    /**
     * 获取 GitHub access_token。
     */
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
        ResponseEntity<GithubTokenResp> response = restTemplate.postForEntity(
                GITHUB.getAccessTokenUrl(),
                request,
                GithubTokenResp.class);

        GithubTokenResp tokenResp = response.getBody();
        if (tokenResp == null || StringUtils.isEmpty(tokenResp.accessToken())) {
            throw new IllegalStateException("GitHub access token request failed: " + response);
        }
        return tokenResp.accessToken();
    }

    /**
     * 获取 GitHub 用户信息。
     */
    private GithubUserInfoResp getUserInfo(String accessToken) {
        log.info("======开始获取github用户信息======");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> request = new HttpEntity<>(headers);
        // 使用 ParameterizedTypeReference 明确指定返回的泛型类型
        ResponseEntity<GithubUserInfoResp> response = restTemplate.exchange(
                GITHUB.getUserInfoUrl(),
                HttpMethod.GET,
                request,
                GithubUserInfoResp.class);

        GithubUserInfoResp tokenResp = response.getBody();
        if (tokenResp == null) {
            throw new IllegalStateException("GitHub user info request failed: " + response);
        }

        return tokenResp;
    }

    /**
     * 获取 GitHub 用户邮箱。
     * GitHub 默认不返回邮箱，需要单独请求。
     */
    private String getPrimaryEmail(String accessToken) {
        try {
            log.info("======开始获取github用户邮箱信息======");
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            HttpEntity<Void> request = new HttpEntity<>(headers);
            ResponseEntity<List<GithubEmailResp>> response = restTemplate.exchange(
                    "https://api.github.com/user/emails",
                    HttpMethod.GET,
                    request,
                    new ParameterizedTypeReference<>() {
                    });

            List<GithubEmailResp> emails = response.getBody();
            if (emails != null) {
                for (GithubEmailResp emailItem : emails) {
                    // 直接通过访问器取值，无强转、无字符串key，编译期校验
                    if (emailItem.primary()) {
                        return emailItem.email();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get GitHub primary email: {}", e.getMessage());
        }
        return null;
    }
}
