package org.liar.zhiliao.auth.service;

import java.util.List;

/**
 * @author liar
 * @since 16/07/26
 */
public interface DeptPermissionService {

    List<Long> getVisibleKbIds(Long deptId);

    List<Long> getVisibleDeptIds(Long deptId);

}
