package org.liar.zhiliao.auth.session;

import java.util.List;

/**
 * Refresh Token 在 Redis 中的数据。
 * 对应 key: auth:refresh:{appId}:{refreshToken}
 */
public record RefreshTokenData(
        String tokenId,
        Long userId,
        String username,
        Long deptId,
        List<Long> visibleDeptIds,
        long issuedAt,
        long expiresAt,
        boolean rotated
) {}
