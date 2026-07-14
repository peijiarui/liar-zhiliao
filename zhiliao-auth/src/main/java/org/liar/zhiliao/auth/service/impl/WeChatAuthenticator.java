package org.liar.zhiliao.auth.service.impl;

import org.liar.zhiliao.auth.service.OAuth2Authenticator;
import org.liar.zhiliao.auth.record.OAuth2UserInfo;

/**
 * 微信扫码登录认证器（预留）。
 *
 * TODO: 接入微信开放平台扫码登录后实现本类。
 * 流程与钉钉扫码一致：生成二维码 → 用户扫码确认 → 回调获取 authCode → 换 token → 获取用户信息。
 * 需配置微信开放平台 AppID/AppSecret 和 redirect-uri。
 */
// @Component  // 预留，暂不注册为 Bean
public class WeChatAuthenticator implements OAuth2Authenticator {

    @Override
    public String provider() {
        return "wechat";
    }

    @Override
    public OAuth2UserInfo authenticate(String authorizationCode) {
        throw new UnsupportedOperationException("微信扫码登录尚未实现");
    }
}
