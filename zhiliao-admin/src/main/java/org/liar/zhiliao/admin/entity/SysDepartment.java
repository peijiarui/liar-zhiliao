package org.liar.zhiliao.admin.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * 部门实体。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("sys_department")
public class SysDepartment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private Long parentId;

    private String tenantId;

    private OffsetDateTime createdAt;
}
