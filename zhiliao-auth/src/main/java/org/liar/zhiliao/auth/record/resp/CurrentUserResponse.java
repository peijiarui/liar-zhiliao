package org.liar.zhiliao.auth.record.resp;

import java.util.List;

public record CurrentUserResponse(
        Long id,
        String loginName,
        String name,
        Long deptId,
        List<Long> visibleDeptIds,
        long expiresAt
) {}
