package org.liar.zhiliao.auth.vo.response;

import org.liar.zhiliao.common.model.CurrentUser;

import java.util.List;

/**
 * 当前登录用户信息
 *
 * @param id             用户 ID
 * @param loginName      登录名
 * @param name           姓名
 * @param role           角色：USER / ADMIN
 * @param deptId         部门 ID
 * @param visibleDeptIds 可见部门 ID 列表
 * @param expiresAt      过期时间戳
 */
public record CurrentUserResponse(
        Long id,
        String loginName,
        String name,
        String role,
        Long deptId,
        List<Long> visibleDeptIds,
        long expiresAt
) {
    public static CurrentUserResponse of(CurrentUser currentUser, long expiresAt) {
        return new CurrentUserResponse(
                currentUser.id(),
                currentUser.loginName(),
                currentUser.name(),
                currentUser.role(),
                currentUser.deptId(),
                currentUser.visibleDeptIds(),
                expiresAt
        );
    }
}
