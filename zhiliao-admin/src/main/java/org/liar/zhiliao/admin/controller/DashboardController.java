package org.liar.zhiliao.admin.controller;

import lombok.RequiredArgsConstructor;
import org.liar.zhiliao.admin.service.DashboardService;
import org.liar.zhiliao.admin.vo.DashboardVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据看板接口。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/admin/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping
    public DashboardVO getDashboard() {
        return dashboardService.getStats();
    }
}
