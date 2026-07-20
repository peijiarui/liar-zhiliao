package org.liar.zhiliao.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.liar.zhiliao.ingestion.entity.ZlAuditLog;
import org.liar.zhiliao.ingestion.mapper.ZlAuditLogMapper;
import org.springframework.web.bind.annotation.*;

/**
 * 审计日志接口。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/admin/audit-logs")
public class AuditLogController {

    private final ZlAuditLogMapper auditLogMapper;

    @GetMapping
    public IPage<ZlAuditLog> page(@RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return auditLogMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<ZlAuditLog>().orderByDesc(ZlAuditLog::getCreatedAt));
    }
}
