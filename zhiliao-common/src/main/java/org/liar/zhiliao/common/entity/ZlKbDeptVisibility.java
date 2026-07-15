package org.liar.zhiliao.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * 知识库-部门可见性关联实体。
 * 控制哪些部门可以查看特定知识库的内容。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("zl_kb_dept_visibility")
public class ZlKbDeptVisibility {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 zl_knowledge_base.id */
    private Long kbId;

    /** 关联 sys_department.id */
    private Long deptId;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
