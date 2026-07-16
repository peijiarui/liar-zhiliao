package org.liar.zhiliao.auth.record.resp;

/**
 * @author liar
 * @since 16/07/26
 */
public record DingTalkUserInfoResp(
        String nick,   // 昵称
        String mobile, // 手机
        String openId, // 用户的openId
        String unionId, // 用户的unionId
        String email,   // 邮箱
        String stateCode) { // 手机号对应的国家号。
}
