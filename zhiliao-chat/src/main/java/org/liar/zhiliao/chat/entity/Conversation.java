package org.liar.zhiliao.chat.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("zl_conversation")
public class Conversation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String memoryId;

    @TableField(fill = FieldFill.INSERT)
    private Long userId;

    private String title;

    @Builder.Default
    private Integer messageCount = 0;

    @Builder.Default
    private Long deptId = 1L;

    @Builder.Default
    private String tenantId = "default";

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
