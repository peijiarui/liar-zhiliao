package org.liar.zhiliao.auth.oauth2;

/**
 * OAuth2 认证成功后返回的用户信息。
 *
 * @param providerUserId 第三方平台中的用户唯一ID（GitHub id / 钉钉 unionId）
 * @param email          用户邮箱（可能为空，钉钉不保证返回）
 * @param name           显示名称
 */
public record OAuth2UserInfo(
        String providerUserId,
        String email,
        String name
) {}
