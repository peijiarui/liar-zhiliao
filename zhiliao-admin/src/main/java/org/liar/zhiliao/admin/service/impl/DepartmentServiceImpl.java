package org.liar.zhiliao.admin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.liar.zhiliao.admin.entity.SysDepartment;
import org.liar.zhiliao.admin.mapper.SysDepartmentMapper;
import org.liar.zhiliao.admin.service.DepartmentService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 部门管理服务实现。
 */
@Service
@RequiredArgsConstructor
public class DepartmentServiceImpl implements DepartmentService {

    private final SysDepartmentMapper departmentMapper;

    @Override
    public List<SysDepartment> list() {
        return departmentMapper.selectList(Wrappers.<SysDepartment>lambdaQuery()
                .orderByAsc(SysDepartment::getId));
    }
}
