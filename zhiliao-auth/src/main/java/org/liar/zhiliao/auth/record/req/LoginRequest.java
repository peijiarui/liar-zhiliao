package org.liar.zhiliao.auth.record.req;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LoginRequest(
        @JsonProperty("login_name") String loginName,
        String password
) {}
