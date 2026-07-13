package org.liar.zhiliao.common.model;

import java.util.Collections;
import java.util.List;

public record CurrentUser(Long id, String username, Long deptId, List<Long> visibleDeptIds) {

    /** 向后兼容构造：无 explicit visibleDeptIds 时仅包含自身 deptId */
    public CurrentUser(Long id, String username, Long deptId) {
        this(id, username, deptId, List.of(deptId));
    }

    /** 创建 CurrentUser，visibleDeptIds 为空时降级为仅自身 dept */
    public static CurrentUser of(Long id, String username, Long deptId, List<Long> visibleDeptIds) {
        List<Long> deptIds = (visibleDeptIds == null || visibleDeptIds.isEmpty())
                ? List.of(deptId)
                : visibleDeptIds;
        return new CurrentUser(id, username, deptId, Collections.unmodifiableList(deptIds));
    }
}
