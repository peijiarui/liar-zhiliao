package org.liar.zhiliao.ingestion.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * 审计日志实体。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("zl_audit_log")
public class ZlAuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String action;

    private String targetType;

    private Long targetId;

    private String detail;

    private Long deptId;

    @Builder.Default
    private String tenantId = "default";

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
