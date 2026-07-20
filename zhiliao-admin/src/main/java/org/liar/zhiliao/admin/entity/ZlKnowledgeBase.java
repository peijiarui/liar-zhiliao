package org.liar.zhiliao.admin.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * 知识库实体。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("zl_knowledge_base")
public class ZlKnowledgeBase {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    private Long deptId;

    @Builder.Default
    private String tenantId = "default";

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
