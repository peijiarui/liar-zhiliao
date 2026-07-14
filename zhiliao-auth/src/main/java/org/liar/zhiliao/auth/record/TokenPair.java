package org.liar.zhiliao.auth.record;

import java.util.List;

/**
 * 登录/刷新接口返回给前端的 token 对。
 *
 * @param accessToken  短期 access token，15 min
 * @param refreshToken 长期 refresh token，7 day，每次刷新轮换
 * @param expiresIn    access token 剩余秒数
 * @param user         用户基本信息（id、loginName、name、deptId、visibleDeptIds）
 */
public record TokenPair(
        String accessToken,
        String refreshToken,
        long expiresIn,
        UserInfo user
) {
    public record UserInfo(Long id, String loginName, String name, Long deptId, List<Long> visibleDeptIds) {}
}
