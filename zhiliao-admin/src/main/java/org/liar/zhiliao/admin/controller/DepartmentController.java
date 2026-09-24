package org.liar.zhiliao.admin.controller;

import lombok.RequiredArgsConstructor;
import org.liar.zhiliao.admin.entity.SysDepartment;
import org.liar.zhiliao.admin.service.DepartmentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 部门管理接口。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/admin/departments")
public class DepartmentController {

    private final DepartmentService departmentService;

    @GetMapping
    public List<SysDepartment> list() {
        return departmentService.list();
    }
}
