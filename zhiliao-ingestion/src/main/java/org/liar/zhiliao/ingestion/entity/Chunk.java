package org.liar.zhiliao.ingestion.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.OffsetDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("chunks")
public class Chunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long docId;

    private String content;

    private String embeddingId;

    @TableField(typeHandler = org.liar.zhiliao.ingestion.config.JsonbTypeHandler.class)
    private String metadata;

    @Builder.Default
    private String tenantId = "default";

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
