package org.liar.zhiliao.common.exception;

import lombok.Getter;

/**
 * 认证/授权异常。
 * 会触发 HTTP 401/403 状态码，前端 axios 拦截器据此跳转登录页。
 */
@Getter
public class AuthException extends RuntimeException {

    private final int httpStatus;

    public AuthException(int httpStatus, String message) {
        super(message);
        this.httpStatus = httpStatus;
    }

}
