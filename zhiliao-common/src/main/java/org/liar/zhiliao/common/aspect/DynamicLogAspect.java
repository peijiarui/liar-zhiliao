package org.liar.zhiliao.common.aspect;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.AspectJExpressionPointcut;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

/**
 * 动态日志切面 V2
 * <p>
 * 使用方式：
 * 1. 在其他模块的 application.yaml 中配置：
 * log.aspect.enabled: true  # 启用日志切面
 * log.aspect.expression: execution(* *..controller..*.*(..))  # 配置切点表达式
 * <p>
 * 2. 如果不配置 log.aspect.enabled 或设置为 false，则日志切面不会生效
 * <p>
 * 3. 切点表达式支持两种配置方式：
 * - 完整的 execution 表达式：execution(* com.example.controller..*.*(..))
 * - 简写包路径：com.example（会自动拼接为 execution(* com.example..controller..*.*(..))）
 * </p>
 *
 * @author Peijiarui
 * @since 2025-05-06
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "log.aspect", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class DynamicLogAspect {

    private final ObjectMapper objectMapper;

    /**
     * 从配置文件读取切点表达式
     * 默认值为拦截所有 controller 包下的方法
     */
    @Value("${log.aspect.expression:execution(* *..controller..*.*(..))}")
    private String pointcutExpression;

    @Value("${log.aspect.log-ip:false}")
    private boolean logIp;

    /**
     * 创建动态切点顾问器
     * 通过编程方式创建切点，支持从配置文件动态读取表达式
     *
     * @return DefaultPointcutAdvisor
     */
    @Bean
    public DefaultPointcutAdvisor dynamicLogAdvisor() {
        // 1. 构建最终的切点表达式
        String finalExpression = buildFinalExpression();
        log.info("✅ 日志切面已启用，拦截表达式：{}", finalExpression);

        // 2. 创建 AspectJ 表达式切点
        AspectJExpressionPointcut pointcut = new AspectJExpressionPointcut();
        pointcut.setExpression(finalExpression);

        // 3. 创建方法拦截器（环绕通知）
        MethodInterceptor interceptor = new LogMethodInterceptor(objectMapper, logIp);

        // 4. 创建顾问器，将切点和拦截器组合
        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor();
        advisor.setPointcut(pointcut);
        advisor.setAdvice(interceptor);

        return advisor;
    }

    /**
     * 构建最终的切点表达式
     * 支持用户配置完整的 execution 表达式或简写的包路径
     *
     * @return 最终的切点表达式
     */
    private String buildFinalExpression() {
        if (StrUtil.isBlank(pointcutExpression)) {
            // 默认：拦截所有项目中，任意包下的 controller 包及子包
            return "execution(* *..controller..*.*(..))";
        } else if (pointcutExpression.trim().startsWith("execution")) {
            // 用户直接配置了完整的 execution 表达式
            return pointcutExpression.trim();
        } else {
            // 用户配置了包路径，自动拼接成 execution 表达式
            // 例如：com.example -> execution(* com.example..controller..*.*(..))
            return "execution(* " + pointcutExpression.trim() + "..controller..*.*(..))";
        }
    }

    /**
     * 日志方法拦截器
     * 实现环绕通知和异常通知的逻辑
     */
    private record LogMethodInterceptor(ObjectMapper objectMapper, boolean logIp) implements MethodInterceptor {

        private static final List<String> SENSITIVE_KEYS = List.of(
                "password", "pwd", "secret", "token", "authorization",
                "apiKey", "api-key", "api_key", "accessKey", "access-key", "access_key",
                "privateKey", "private-key", "private_key"
        );

        private static final int MAX_ARGS_LENGTH = 500;

        @Override
        public Object invoke(MethodInvocation invocation) throws Throwable {
            Method method = invocation.getMethod();
            Object[] args = invocation.getArguments();
            Object target = invocation.getThis();
            String className = target != null ? target.getClass().getName() : "Unknown";
            String methodName = method.getName();

            long startTime = System.currentTimeMillis();

            String traceId = getOrCreateTraceId();
            MDC.put("traceId", traceId);

            try {
                logMethodStart(traceId, className, methodName, args);

                Object result = invocation.proceed();

                long duration = System.currentTimeMillis() - startTime;
                logMethodEnd(traceId, className, methodName, duration);

                return result;

            } catch (Throwable e) {
                logMethodException(traceId, className, methodName, args, e);
                throw e;
            } finally {
                MDC.remove("traceId");
            }
        }

        /**
         * 获取请求的追踪id，如果没有则创建一个
         *
         * @return traceId
         */
        private String getOrCreateTraceId() {
            try {
                ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
                if (attributes != null) {
                    HttpServletRequest request = attributes.getRequest();
                    String traceId = request.getHeader("X-Request-ID");
                    if (StrUtil.isNotBlank(traceId)) return traceId;
                    traceId = request.getHeader("X-Trace-ID");
                    if (StrUtil.isNotBlank(traceId)) return traceId;
                }
            } catch (Exception ignored) {
            }
            return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        /**
         * 打印方法调用日志
         *
         * @param traceId    追踪id
         * @param className  类名
         * @param methodName 方法名
         * @param args       参数列表
         */
        private void logMethodStart(String traceId, String className, String methodName, Object[] args) {
            String clientIp = logIp ? getClientIp() : null;
            String argsStr = serializeArgs(args);
            if (logIp) {
                log.info("请求IP:【{}】，traceId:{} 调用{}的【{}】方法开始，\n参数列表:【{}】",
                        clientIp, traceId, className, methodName, argsStr);
            } else {
                log.info("traceId:{} 调用{}的【{}】方法开始，\n参数列表:【{}】",
                        traceId, className, methodName, argsStr);
            }
        }

        /**
         * 打印方法结束日志
         *
         * @param traceId    追踪id
         * @param className  类名
         * @param methodName 方法名
         * @param duration   耗时
         */
        private void logMethodEnd(String traceId, String className, String methodName, long duration) {
            log.info("traceId:{} 调用{}的【{}】方法结束，\n耗时：【{}】ms",
                    traceId, className, methodName, duration);
        }

        /**
         * 打印方法异常日志
         *
         * @param traceId    追踪id
         * @param className  类名
         * @param methodName 方法名
         * @param args       参数列表
         * @param e          异常
         */
        private void logMethodException(String traceId, String className, String methodName, Object[] args, Throwable e) {
            log.error("traceId:{} 方法异常：调用{}的【{}】方法，异常信息：{} \n参数列表：{}",
                    traceId, className, methodName, e.getMessage(), serializeArgs(args));
        }

        /**
         * 将参数列表转换为字符串
         *
         * @param args 参数列表
         * @return 参数列表的字符串表示
         */
        private String serializeArgs(Object[] args) {
            try {
                String json = objectMapper.writeValueAsString(args);
                // json = maskSensitiveFields(json);    // 敏感字段脱敏，暂时注释
                return truncate(json);
            } catch (Exception e) {
                return argsToString(args);
            }
        }

        /**
         * 敏感字段脱敏
         *
         * @param json json字符串
         * @return 脱敏后的json字符串
         */
        private String maskSensitiveFields(String json) {
            String result = json;
            for (String key : SENSITIVE_KEYS) {
                result = result.replaceAll(
                        "\"" + key + "\"\\s*:\\s*\"[^\"]*\"",
                        "\"" + key + "\":\"****\""
                );
                result = result.replaceAll(
                        "\"" + key + "\"\\s*:\\s*[^,\\]}]+",
                        "\"" + key + "\":\"****\""
                );
            }
            return result;
        }

        /**
         * 截取字符串，防止参数过长
         *
         * @param str 字符串
         * @return 截取后的字符串
         */
        private String truncate(String str) {
            if (str.length() <= MAX_ARGS_LENGTH) {
                return str;
            }
            return str.substring(0, MAX_ARGS_LENGTH) + "... (truncated, total length: " + str.length() + ")";
        }

        /**
         * 将参数列表转换为字符串
         *
         * @param args 参数列表
         * @return 参数列表的字符串表示
         */
        private String argsToString(Object[] args) {
            if (args == null || args.length == 0) {
                return "[]";
            }
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(", ");
                try {
                    if (args[i] != null) {
                        sb.append(args[i]);
                    } else {
                        sb.append("null");
                    }
                } catch (Exception e) {
                    sb.append("<toString() error: ").append(e.getMessage()).append(">");
                }
            }
            sb.append("]");
            return sb.toString();
        }

        /**
         * 从请求头中获取IP地址
         *
         * @return IP地址
         */
        private String getClientIp() {
            try {
                ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
                if (attributes == null) {
                    return "Unknown";
                }
                HttpServletRequest request = attributes.getRequest();

                String ip = getIpFromHeader(request, "X-Forwarded-For");
                if (ip != null) {
                    return ip;
                }

                ip = getIpFromHeader(request, "X-Real-IP");
                if (ip != null) {
                    return ip;
                }

                ip = getIpFromHeader(request, "Proxy-Client-IP");
                if (ip != null) {
                    return ip;
                }

                ip = getIpFromHeader(request, "WL-Proxy-Client-IP");
                if (ip != null) {
                    return ip;
                }

                String remoteAddr = request.getRemoteAddr();
                if ("0:0:0:0:0:0:0:1".equals(remoteAddr) || "::1".equals(remoteAddr)) {
                    return "127.0.0.1";
                }
                return isIPv4(remoteAddr) ? remoteAddr : "Unknown";
            } catch (Exception e) {
                return "Unknown";
            }
        }

        /**
         * 从请求头中获取IP地址
         *
         * @param request    请求对象
         * @param headerName 请求头名称
         * @return IP地址
         */
        private String getIpFromHeader(HttpServletRequest request, String headerName) {
            String ip = request.getHeader(headerName);
            if (StrUtil.isBlank(ip) || "unknown".equalsIgnoreCase(ip)) {
                return null;
            }

            int index = ip.indexOf(',');
            if (index != -1) {
                ip = ip.substring(0, index).trim();
            }

            return isIPv4(ip) ? ip : null;
        }

        private boolean isIPv4(String ip) {
            if (StrUtil.isBlank(ip)) {
                return false;
            }
            String ipv4Pattern = "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$";
            return ip.matches(ipv4Pattern);
        }
    }
}
