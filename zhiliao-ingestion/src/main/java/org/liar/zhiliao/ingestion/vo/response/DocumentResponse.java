package org.liar.zhiliao.ingestion.vo.response;

import org.liar.zhiliao.ingestion.entity.ZlDocument;

import java.time.OffsetDateTime;

/**
 * @author Pei
 * @since 2026-07-07
 */
public record DocumentResponse(

        Long id,    // 文档ID
        String fileName, // 文件名
        String fileType, // 文件类型
        String status, // 状态
        Long fileSize, // 文件大小
        Integer chunkCount, // 分块数量
        OffsetDateTime createdAt // 创建时间
) {

    public static DocumentResponse of(ZlDocument doc) {
        return new DocumentResponse(
                doc.getId(),
                doc.getFileName(),
                doc.getFileType(),
                doc.getStatus(),
                doc.getFileSize(),
                doc.getChunkCount(),
                doc.getCreatedAt()
        );
    }

}

