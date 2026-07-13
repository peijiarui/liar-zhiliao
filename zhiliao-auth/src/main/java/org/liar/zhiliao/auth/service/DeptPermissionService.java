package org.liar.zhiliao.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.liar.zhiliao.auth.entity.ZlKbDeptVisibility;
import org.liar.zhiliao.auth.mapper.ZlKbDeptVisibilityMapper;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 部门权限查询服务。
 * 查询指定部门可见的知识库 ID 列表。
 */
@Service
@RequiredArgsConstructor
public class DeptPermissionService {

    private final ZlKbDeptVisibilityMapper visibilityMapper;

    /**
     * 查询指定部门可见的知识库 ID 列表。
     *
     * @param deptId 部门 ID
     * @return 可见知识库 ID 列表，无权限时返回空列表
     */
    public List<Long> getVisibleKbIds(Long deptId) {
        List<ZlKbDeptVisibility> visibilities = visibilityMapper.selectList(
                Wrappers.<ZlKbDeptVisibility>lambdaQuery()
                        .eq(ZlKbDeptVisibility::getDeptId, deptId));
        if (visibilities.isEmpty()) {
            return Collections.emptyList();
        }
        return visibilities.stream()
                .map(ZlKbDeptVisibility::getKbId)
                .toList();
    }
}
