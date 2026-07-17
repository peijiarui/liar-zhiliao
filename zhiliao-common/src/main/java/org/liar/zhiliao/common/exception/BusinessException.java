package org.liar.zhiliao.common.exception;

import lombok.Getter;

/**
 * @author Peijiarui
 * @since 2019/12/9
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int status;

    public BusinessException(String message) {
        super(message);
        this.status = 500;
    }

    public BusinessException(int status, String message) {
        super(message);
        this.status = status;
    }

}
