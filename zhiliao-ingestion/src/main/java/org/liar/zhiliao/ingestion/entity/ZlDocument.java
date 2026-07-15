package org.liar.zhiliao.ingestion.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.liar.zhiliao.ingestion.enums.DocumentStatusEnum;

import java.time.OffsetDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("zl_document")
public class ZlDocument {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long kbId;

    private String fileName;

    private String fileType;

    @TableField
    @Builder.Default
    private String status = DocumentStatusEnum.UPLOADED.getStatus();

    private String minioKey;

    private Long fileSize;

    private String md5;

    private Long deptId;

    private Integer chunkCount;

    private String tenantId;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
