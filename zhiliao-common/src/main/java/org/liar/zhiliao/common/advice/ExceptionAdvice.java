package org.liar.zhiliao.common.advice;


import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.common.handler.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 异常处理
 *
 * @author Peijiarui
 * @since 2019/12/17
 */
@Slf4j
@RestControllerAdvice
public class ExceptionAdvice extends ExceptionHandler {

}