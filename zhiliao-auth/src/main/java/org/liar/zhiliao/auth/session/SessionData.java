package org.liar.zhiliao.auth.session;

import java.util.List;

/**
 * Access Token 在 Redis 中的会话数据。
 * 对应 key: auth:session:{appId}:{accessToken}
 */
public record SessionData(
        String tokenId,
        Long userId,
        String username,
        Long deptId,
        List<Long> visibleDeptIds,
        String refreshTokenId,
        long issuedAt,
        long expiresAt
) {}
