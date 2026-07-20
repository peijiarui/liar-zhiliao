package org.liar.zhiliao.auth.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.auth.config.AuthProperties;
import org.liar.zhiliao.auth.record.RefreshTokenData;
import org.liar.zhiliao.auth.record.SessionData;
import org.liar.zhiliao.auth.record.TokenPair;
import org.liar.zhiliao.auth.service.TokenService;
import org.liar.zhiliao.common.model.CurrentUser;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Token 服务：生成、存储、查询、删除、刷新不透明 token。
 * <p>
 * Redis key 结构：
 * <ul>
 *   <li>auth:session:{appId}:{accessToken}  → SessionData JSON</li>
 *   <li>auth:refresh:{appId}:{refreshToken} → RefreshTokenData JSON</li>
 *   <li>auth:user:{userId}:sessions         → SET of accessToken（预留踢人）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenServiceImpl implements TokenService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AuthProperties props;

    /** 签发新 access + refresh token 对，存入 Redis */
    @Override
    public TokenPair issueToken(CurrentUser user) {
        String accessToken = generateToken();
        String refreshToken = generateToken();
        String accessTokenId = UUID.randomUUID().toString();
        String refreshTokenId = UUID.randomUUID().toString();

        long now = System.currentTimeMillis();
        long accessExpiresAt = now + props.getAccessTokenTtlSeconds() * 1000L;
        long refreshExpiresAt = now + props.getRefreshTokenTtlSeconds() * 1000L;

        SessionData session = new SessionData(
                accessTokenId, user.id(), user.loginName(), user.name(), user.role(), user.deptId(),
                user.visibleDeptIds(), refreshTokenId, now, accessExpiresAt);
        RefreshTokenData refresh = new RefreshTokenData(
                refreshTokenId, user.id(), user.loginName(), user.name(), user.role(), user.deptId(),
                user.visibleDeptIds(), now, refreshExpiresAt, false);

        store(sessionKey(accessToken), session, props.getAccessTokenTtlSeconds());
        store(refreshKey(refreshToken), refresh, props.getRefreshTokenTtlSeconds());

        return new TokenPair(
                accessToken, refreshToken,
                props.getAccessTokenTtlSeconds(),
                new TokenPair.UserInfo(user.id(), user.loginName(), user.name(), user.role(), user.deptId(), user.visibleDeptIds()));
    }

    /** 查询 access token 对应会话，无效返回 null */
    @Override
    public SessionData getSession(String accessToken) {
        String json = redis.opsForValue().get(sessionKey(accessToken));
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, SessionData.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse session data: {}", e.getMessage());
            return null;
        }
    }

    /** 吊销 access token 及其关联的 refresh token */
    @Override
    public void revoke(String accessToken) {
        SessionData session = getSession(accessToken);
        if (session == null) return;
        redis.delete(sessionKey(accessToken));
        log.info("Revoked session: userId={}, accessTokenId={}", session.userId(), session.tokenId());
    }

    /** 用 refresh token 换新 token 对；旧 refresh token 立即作废（rotation） */
    @Override
    public TokenPair refresh(String refreshToken) {
        String json = redis.opsForValue().get(refreshKey(refreshToken));
        if (json == null) {
            throw new IllegalStateException("refresh_token_invalid");
        }
        RefreshTokenData old;
        try {
            old = objectMapper.readValue(json, RefreshTokenData.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("refresh_token_invalid", e);
        }
        if (old.rotated()) {
            log.warn("Detected replayed refresh token: userId={}, rtId={}", old.userId(), old.tokenId());
            throw new IllegalStateException("refresh_token_rotated");
        }

        // 标记旧 refresh token 为已轮换（防重放）
        RefreshTokenData rotated = new RefreshTokenData(
                old.tokenId(), old.userId(), old.loginName(), old.name(), old.role(), old.deptId(),
                old.visibleDeptIds(), old.issuedAt(), old.expiresAt(), true);
        store(refreshKey(refreshToken), rotated, props.getRefreshTokenTtlSeconds());

        CurrentUser user = new CurrentUser(old.userId(), old.loginName(), old.name(), old.role(), old.deptId(), old.visibleDeptIds());
        return issueToken(user);
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }

    private String sessionKey(String token) {
        return "auth:session:" + props.getAppId() + ":" + token;
    }

    private String refreshKey(String token) {
        return "auth:refresh:" + props.getAppId() + ":" + token;
    }

    private void store(String key, Object value, long ttlSeconds) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value),
                    ttlSeconds, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize session data", e);
        }
    }
}
