package org.liar.zhiliao.auth.oauth2;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * OAuth2 配置属性，绑定 oauth2.* 前缀。
 * 每个提供商包含 client-id、client-secret 和 redirect-uri。
 */
@Data
@Component
@ConfigurationProperties(prefix = "oauth2")
public class OAuth2Config {

    private ProviderConfig github = new ProviderConfig();
    private ProviderConfig dingtalk = new ProviderConfig();
    private ProviderConfig wechat = new ProviderConfig();

    @Data
    public static class ProviderConfig {
        private String clientId;
        private String clientSecret;
        private String redirectUri;
    }
}
