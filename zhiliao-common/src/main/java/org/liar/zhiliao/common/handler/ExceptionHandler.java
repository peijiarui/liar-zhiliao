package org.liar.zhiliao.common.handler;

import cn.hutool.core.bean.BeanUtil;
import jakarta.annotation.Nonnull;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.common.exception.AuthException;
import org.liar.zhiliao.common.exception.BusinessException;
import org.liar.zhiliao.common.exception.UtilException;
import org.liar.zhiliao.common.result.ResponseResult;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.json.MappingJackson2JsonView;


/**
 * 全局异常处理
 *
 * @author Peijiarui
 * @since 2019/12/17
 */
@Slf4j
public class ExceptionHandler implements HandlerExceptionResolver {

    @Override
    public ModelAndView resolveException(@Nonnull HttpServletRequest request, @Nonnull HttpServletResponse httpServletResponse, Object handler, @Nonnull Exception e) {
        ModelAndView mv = new ModelAndView();
        MappingJackson2JsonView view = new MappingJackson2JsonView();
        if (e instanceof AuthException auth) {
            // 认证异常 → 设置 HTTP 状态码，前端拦截器据此跳登录
            log.error("请求：{} ,认证异常：{}", request.getMethod() + ":" + request.getRequestURI(), e.getMessage());
            httpServletResponse.setStatus(auth.getHttpStatus());
            view.setAttributesMap(BeanUtil.beanToMap(ResponseResult.fail(auth.getHttpStatus(), e.getMessage())));
        } else if (e instanceof BusinessException biz) {
            // 业务异常 → HTTP 200，错误信息在 body 中
            log.error("请求：{} ,业务异常：{}", request.getMethod() + ":" + request.getRequestURI(), e.getMessage());
            view.setAttributesMap(BeanUtil.beanToMap(ResponseResult.fail(biz.getStatus(), e.getMessage())));
        } else if (e instanceof UtilException util) {
            // 工具类异常 → HTTP 200
            log.error("请求：{} ,工具类异常：{}", request.getMethod() + ":" + request.getRequestURI(), e.getMessage());
            view.setAttributesMap(BeanUtil.beanToMap(ResponseResult.fail(util.getStatus(), e.getMessage())));
        } else {
            // 未知异常 → HTTP 500
            log.error("请求：{} ,系统异常：{}", request.getMethod() + ":" + request.getRequestURI(), e.getMessage());
            httpServletResponse.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            view.setAttributesMap(BeanUtil.beanToMap(ResponseResult.fail(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "系统异常【" + e.getClass().getSimpleName() + "】")));
        }

        mv.setView(view);
        return mv;
    }
}
