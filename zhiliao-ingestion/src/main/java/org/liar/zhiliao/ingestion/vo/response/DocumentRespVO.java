package org.liar.zhiliao.ingestion.vo.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * @author Pei
 * @since 2026-07-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentRespVO {

    /**
     * 文档ID
     */
    private Long id;
    /**
     * 文件名
     */
    private String fileName;
    /**
     * 文件类型
     */
    private String fileType;
    /**
     * 状态
     */
    private String status;
    /**
     * 文件大小
     */
    private Long fileSize;
    /**
     * 分块数量
     */
    private Integer chunkCount;
    /**
     * 创建时间
     */
    private OffsetDateTime createdAt;

}

