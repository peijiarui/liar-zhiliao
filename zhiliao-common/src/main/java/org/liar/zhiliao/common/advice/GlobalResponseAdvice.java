package org.liar.zhiliao.common.advice;

import jakarta.annotation.Nonnull;
import org.liar.zhiliao.common.result.ResponseResult;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 全局统一响应体包装。
 * Controller 返回原始 POJO，自动包装为 ResponseResult，前端统一解析 {status, message, data}。
 */
@RestControllerAdvice
public class GlobalResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType,
                            @Nonnull Class<? extends HttpMessageConverter<?>> converterType) {
        Class<?> type = returnType.getParameterType();
        // 跳过已包装的 ResponseResult、ResponseEntity、String（String 不走 Jackson 序列化）
        return !ResponseResult.class.isAssignableFrom(type)
                && !org.springframework.http.ResponseEntity.class.isAssignableFrom(type)
                && !String.class.isAssignableFrom(type);
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  @Nonnull MethodParameter returnType,
                                  @Nonnull MediaType selectedContentType,
                                  @Nonnull Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  @Nonnull ServerHttpRequest request,
                                  @Nonnull ServerHttpResponse response) {
        return ResponseResult.ok(body);
    }
}
