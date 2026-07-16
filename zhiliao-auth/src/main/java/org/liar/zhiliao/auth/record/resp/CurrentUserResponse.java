package org.liar.zhiliao.auth.record.resp;

import org.liar.zhiliao.common.model.CurrentUser;

import java.util.List;

public record CurrentUserResponse(
        Long id,
        String loginName,
        String name,
        Long deptId,
        List<Long> visibleDeptIds,
        long expiresAt
) {
    public static CurrentUserResponse of(CurrentUser currentUser, long expiresAt) {
        return new CurrentUserResponse(
                currentUser.id(),
                currentUser.loginName(),
                currentUser.name(),
                currentUser.deptId(),
                currentUser.visibleDeptIds(),
                expiresAt
        );
    }
}
