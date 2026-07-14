package org.liar.zhiliao.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 认证相关配置。
 * 对应 application.yaml 中 zhiliao.auth.* 配置项。
 */
@Data
@Component
@ConfigurationProperties(prefix = "zhiliao.auth")
public class AuthProperties {
    /** SSO 应用标识，预留多应用共享 session 时区分 */
    private String appId = "zhiliao";
    /** Access Token 有效期（秒），默认 15 分钟 */
    private long accessTokenTtlSeconds = 900L;
    /** Refresh Token 有效期（秒），默认 7 天 */
    private long refreshTokenTtlSeconds = 604800L;
    /** OAuth state 参数有效期（秒），默认 5 分钟 */
    private long oauthStateTtlSeconds = 300L;
}
