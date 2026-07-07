package org.liar.zhiliao.ingestion.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentMessage {
    private Long documentId;
    private String minioKey;
    private String fileName;
}
