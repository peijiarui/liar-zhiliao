package org.liar.zhiliao.ingestion.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.OffsetDateTime;

@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("documents")
public class Document {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long kbId;

    private String fileName;

    private String fileType;

    @TableField
    @Builder.Default
    private String status = "UPLOADED";

    private String minioKey;

    private Long fileSize;

    private String md5;

    private Integer chunkCount;

    private String tenantId;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
