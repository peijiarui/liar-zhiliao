package org.liar.zhiliao.auth.vo.request;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 登录请求参数。
 *
 * @param loginName 登录名
 * @param password  密码
 */
public record LoginRequest(
        @JsonProperty("login_name") String loginName, // 登录名
        String password // 密码
) {
}
