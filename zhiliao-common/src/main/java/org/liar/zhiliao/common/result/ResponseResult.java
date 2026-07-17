package org.liar.zhiliao.common.result;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * @author liar
 * @since 17/07/26
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResponseResult<T>(int status, String message, T data) {

    public static <T> ResponseResult<T> ok() {
        return ok(null);
    }

    public static <T> ResponseResult<T> ok(T data) {
        return new ResponseResult<>(200, "success", data);
    }

    public static ResponseResult<Void> fail(int status, String message) {
        return new ResponseResult<>(status, message, null);
    }

}
