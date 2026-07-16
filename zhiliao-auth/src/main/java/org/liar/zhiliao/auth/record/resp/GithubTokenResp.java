package org.liar.zhiliao.auth.record.resp;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @author liar
 * @since 16/07/26
 */
public record GithubTokenResp(@JsonProperty("access_token") String accessToken) {
}
