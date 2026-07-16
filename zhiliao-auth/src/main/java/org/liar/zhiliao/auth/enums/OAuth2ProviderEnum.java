package org.liar.zhiliao.auth.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @author liar
 * @since 16/07/26
 */
@Getter
@AllArgsConstructor
public enum OAuth2ProviderEnum {

    // 钉钉登录
    DINGTALK(
            "dingtalk",
            "钉钉扫码登录",
            "https://api.dingtalk.com/v1.0/oauth2/userAccessToken",
            "https://api.dingtalk.com/v1.0/contact/users/me",
            ""
    ),
    // GitHub登录
    GITHUB(
            "github",
            "GitHub OAuth登录",
            "https://github.com/login/oauth/access_token",
            "https://api.github.com/user",
            "https://api.github.com/user/emails"
    ),
    // 微信登录
    WECHAT(
            "wechat",
            "微信扫码登录",
            "https://api.weixin.qq.com/sns/oauth2/access_token",
            "https://api.weixin.qq.com/sns/userinfo",
            ""
    );

    /** 厂商唯一标识（和配置文件key对应） */
    private final String provider;
    /** 厂商描述 */
    private final String desc;
    /** 获取accessToken接口地址 */
    private final String accessTokenUrl;
    /** 获取用户信息接口地址 */
    private final String userInfoUrl;
    /** 获取用户信息接口地址 */
    private final String emailInfoUrl;

}
