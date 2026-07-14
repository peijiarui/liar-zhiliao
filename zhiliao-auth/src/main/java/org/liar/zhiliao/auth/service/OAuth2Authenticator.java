package org.liar.zhiliao.auth.service;

import org.liar.zhiliao.auth.record.OAuth2UserInfo;

/**
 * OAuth2 认证器接口。
 * 每个 OAuth 提供商实现一个子类，通过 provider() 名称区分。
 * 新增提供商时只需实现本接口并注册为 Spring Bean，OAuth2Controller 自动发现。
 */
public interface OAuth2Authenticator {

    /** OAuth 提供商名称，如 "github"、"dingtalk"、"wechat" */
    String provider();

    /** 用授权码换取用户信息 */
    OAuth2UserInfo authenticate(String authorizationCode);
}
