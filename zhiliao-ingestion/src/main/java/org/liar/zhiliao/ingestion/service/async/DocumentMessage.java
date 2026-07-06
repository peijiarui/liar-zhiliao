package org.liar.zhiliao.ingestion.service.async;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentMessage {
    private Long documentId;
    private String minioKey;
    private String fileName;
}
