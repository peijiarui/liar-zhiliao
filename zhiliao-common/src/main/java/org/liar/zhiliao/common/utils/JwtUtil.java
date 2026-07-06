package org.liar.zhiliao.common.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SecurityException;
import org.liar.zhiliao.common.model.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {
    private static final String USERNAME_CLAIM = "username";
    private static final String DEPT_ID_CLAIM = "deptId";

    private final SecretKey key;
    private final long expirationMs;

    public JwtUtil(@Value("${zhiliao.jwt.secret:zhiliao-default-secret-key-for-hmac-sha256-at-least-256-bits}") String secret,
                   @Value("${zhiliao.jwt.expiration-ms:86400000}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    public String generateToken(CurrentUser user) {
        Date now = new Date();
        return Jwts.builder()
                .subject(user.id().toString())
                .claim(USERNAME_CLAIM, user.username())
                .claim(DEPT_ID_CLAIM, user.deptId())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationMs))
                .signWith(key)
                .compact();
    }

    public CurrentUser parseToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return new CurrentUser(
                safeParseLong(claims.getSubject()),
                claims.get(USERNAME_CLAIM, String.class),
                claims.get(DEPT_ID_CLAIM, Long.class)
        );
    }

    private static Long safeParseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Validates the given JWT token.
     *
     * @param token the JWT token to validate
     * @return {@code true} if the token is valid and not expired, {@code false} otherwise
     */
    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
