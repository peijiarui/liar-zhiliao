package org.liar.zhiliao.auth.record.resp;

/**
 * @author liar
 * @since 16/07/26
 */
public record GithubUserInfoResp(
        String id,   // github的用户id
        String login) { // 登录名
}
