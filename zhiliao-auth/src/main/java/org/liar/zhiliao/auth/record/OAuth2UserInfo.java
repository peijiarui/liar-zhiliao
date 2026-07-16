package org.liar.zhiliao.auth.record;

import org.liar.zhiliao.auth.record.resp.DingTalkUserInfoResp;
import org.liar.zhiliao.auth.record.resp.GithubUserInfoResp;

/**
 * OAuth2 认证成功后返回的用户信息。
 *
 * @param providerUserId 第三方平台中的用户唯一ID（GitHub id / 钉钉 unionId）
 * @param email          用户邮箱（可能为空，钉钉不保证返回）
 * @param phone          用户手机号（可能为空，GitHub不返回）
 * @param loginName      登录名
 * @param name           显示名称
 */
public record OAuth2UserInfo(
        String providerUserId,
        String email,
        String phone,
        String loginName,
        String name
) {
    public static OAuth2UserInfo of(DingTalkUserInfoResp userInfo) {
        return new OAuth2UserInfo(
                userInfo.unionId(),
                userInfo.email(),
                userInfo.mobile(),
                userInfo.nick(),
                userInfo.nick());
    }

    public static OAuth2UserInfo of(GithubUserInfoResp userInfo, String email) {
        return new OAuth2UserInfo(
                userInfo.id(),
                email,
                null,
                userInfo.login(),
                userInfo.login());
    }
}
