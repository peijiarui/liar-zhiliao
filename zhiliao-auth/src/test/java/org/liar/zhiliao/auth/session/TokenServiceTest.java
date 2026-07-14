package org.liar.zhiliao.auth.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.liar.zhiliao.auth.config.AuthProperties;
import org.liar.zhiliao.common.model.CurrentUser;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TokenServiceTest {

    private AuthProperties props;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        props = new AuthProperties();
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        tokenService = new TokenService(redis, new ObjectMapper(), props);
    }

    @Test
    void issue_shouldStoreBothTokensAndReturnPair() {
        CurrentUser user = new CurrentUser(1L, "alice", 2L, List.of(1L, 2L));

        TokenPair pair = tokenService.issue(user);

        assertThat(pair.accessToken()).isNotBlank();
        assertThat(pair.refreshToken()).isNotBlank();
        assertThat(pair.expiresIn()).isEqualTo(900L);
        assertThat(pair.user().id()).isEqualTo(1L);
        verify(valueOps, times(2)).set(anyString(), anyString(), anyLong(), any());
    }

    @Test
    void getSession_shouldReturnNullWhenTokenNotInRedis() {
        when(valueOps.get("auth:session:zhiliao:nonexistent")).thenReturn(null);

        SessionData session = tokenService.getSession("nonexistent");

        assertThat(session).isNull();
    }

    @Test
    void refresh_shouldRotateRefreshToken() throws Exception {
        // 准备一个已存在的 refresh token
        RefreshTokenData existing = new RefreshTokenData(
                "old-rt-id", 1L, "alice", 2L, List.of(1L, 2L),
                System.currentTimeMillis(), System.currentTimeMillis() + 86400000L, false);
        when(valueOps.get(contains("auth:refresh:zhiliao:")))
                .thenReturn(new ObjectMapper().writeValueAsString(existing));

        TokenPair newPair = tokenService.refresh("old-refresh-token");

        assertThat(newPair.refreshToken()).isNotEqualTo("old-refresh-token");
        assertThat(newPair.accessToken()).isNotBlank();
    }

    @Test
    void refresh_shouldThrowWhenTokenRotated() throws Exception {
        RefreshTokenData rotated = new RefreshTokenData(
                "rt-id", 1L, "alice", 2L, List.of(1L, 2L),
                System.currentTimeMillis(), System.currentTimeMillis() + 86400000L, true);
        when(valueOps.get(contains("auth:refresh:zhiliao:")))
                .thenReturn(new ObjectMapper().writeValueAsString(rotated));

        assertThatThrownBy(() -> tokenService.refresh("rotated-token"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rotated");
    }
}
