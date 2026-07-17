package org.liar.zhiliao.common.exception;

import lombok.Getter;

/**
 * @author Peijiarui
 * @since 2019/12/9
 */
@Getter
public class UtilException extends RuntimeException {

    private int status = 500;

    public UtilException(String message) {
        super(message);
    }

    public UtilException(int status, String message) {
        super(message);
        this.status = status;
    }

}
