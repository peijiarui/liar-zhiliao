package org.liar.zhiliao.ingestion.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.OffsetDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("zl_chunk")
public class ZlChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long docId;

    private Long deptId;

    private String content;

    private String embeddingId;

    @TableField(typeHandler = org.liar.zhiliao.ingestion.config.JsonbTypeHandler.class)
    private String metadata;

    private Long parentId;

    @Builder.Default
    private String chunkType = "child";

    @Builder.Default
    private String tenantId = "default";

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
