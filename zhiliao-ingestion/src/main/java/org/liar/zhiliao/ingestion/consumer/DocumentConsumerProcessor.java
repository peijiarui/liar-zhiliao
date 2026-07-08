package org.liar.zhiliao.ingestion.consumer;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.ingestion.config.MinIOConfig;
import org.liar.zhiliao.ingestion.entity.ZlChunk;
import org.liar.zhiliao.ingestion.entity.ZlDocument;
import org.liar.zhiliao.ingestion.enums.DocumentStatusEnum;
import org.liar.zhiliao.ingestion.mapper.ZlChunkMapper;
import org.liar.zhiliao.ingestion.mapper.ZlDocumentMapper;
import org.liar.zhiliao.ingestion.model.DocumentMessage;
import org.liar.zhiliao.ingestion.service.DocumentParser;
import org.liar.zhiliao.ingestion.service.DocumentSplitter;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@AllArgsConstructor
public class DocumentConsumerProcessor {

    private ApplicationContext context;
    private final MinioClient minioClient;
    private final MinIOConfig minIOConfig;
    private final DocumentParser documentParser;
    private final DocumentSplitter documentSplitter;
    private final ZlDocumentMapper documentMapper;
    private final ZlChunkMapper chunkMapper;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> milvusEmbeddingStore;

    public void process(DocumentMessage message) {
        Long documentId = message.getDocumentId();
        log.info("Processing document {}: {}", documentId, message.getFileName());

        ZlDocument doc = documentMapper.selectById(documentId);
        if (doc == null) {
            log.warn("Document not found: {}", documentId);
            return;
        }

        // 1. Update status to PROCESSING
        doc.setStatus(DocumentStatusEnum.PROCESSING.getStatus());
        documentMapper.updateById(doc);

        try {
            // 2. Download from MinIO
            var args = GetObjectArgs.builder()
                    .bucket(minIOConfig.getBucket())
                    .object(message.getMinioKey())
                    .build();
            var object = minioClient.getObject(args);

            // 3. Parse with Tika
            String text = documentParser.parse(object, message.getFileName());

            // 4. Split into chunks
            List<TextSegment> segments = documentSplitter.split(text, documentId.toString());
            log.info("Document {} split into {} chunks", documentId, segments.size());

            // 5. Embed all chunks via LangChain4j EmbeddingModel
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();

            // 6. Store vectors in Milvus via LangChain4j EmbeddingStore
            log.info("embeddingStore class: {}", milvusEmbeddingStore.getClass().getName());
            List<String> vectorIds = milvusEmbeddingStore.addAll(embeddings, segments);
            log.info("addAll returned {} vector IDs: {}", vectorIds.size(), vectorIds);

            // 7. Save chunks to PG via MyBatis-Plus
            for (int i = 0; i < segments.size(); i++) {
                ZlChunk zlChunk = ZlChunk.builder()
                        .docId(documentId)
                        .content(segments.get(i).text())
                        .embeddingId(vectorIds.get(i))
                        .metadata("{\"index\": " + i + ", \"fileName\": \"" + message.getFileName() + "\"}")
                        .build();
                chunkMapper.insert(zlChunk);
            }

            // 8. Update document status to COMPLETED
            doc.setStatus(DocumentStatusEnum.COMPLETED.getStatus());
            doc.setChunkCount(segments.size());
            documentMapper.updateById(doc);

            log.info("Document {} processed successfully: {} chunks", documentId, segments.size());
        } catch (Exception e) {
            log.error("Error processing document {}: {}", documentId, e.getMessage(), e);
            doc.setStatus(DocumentStatusEnum.FAILED.getStatus());
            documentMapper.updateById(doc);
        }
    }
}
