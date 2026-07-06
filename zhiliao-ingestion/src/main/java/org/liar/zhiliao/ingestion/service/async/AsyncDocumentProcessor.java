package org.liar.zhiliao.ingestion.service.async;

import dev.langchain4j.data.segment.TextSegment;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.ingestion.config.MinIOConfig;
import org.liar.zhiliao.ingestion.model.Chunk;
import org.liar.zhiliao.ingestion.model.Document;
import org.liar.zhiliao.ingestion.repository.ChunkMapper;
import org.liar.zhiliao.ingestion.repository.DocumentMapper;
import org.liar.zhiliao.ingestion.service.DocumentParser;
import org.liar.zhiliao.ingestion.service.DocumentSplitter;
import org.liar.zhiliao.retrieval.service.EmbeddingService;
import org.liar.zhiliao.retrieval.service.VectorStore;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@AllArgsConstructor
public class AsyncDocumentProcessor {

    private final MinioClient minioClient;
    private final MinIOConfig minIOConfig;
    private final DocumentParser documentParser;
    private final DocumentSplitter documentSplitter;
    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;
    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;

    public void process(DocumentMessage message) throws Exception {
        Long documentId = message.getDocumentId();
        log.info("Processing document {}: {}", documentId, message.getFileName());

        // 1. Update status to PROCESSING
        Document doc = Optional.ofNullable(documentMapper.selectById(documentId))
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));
        doc.setStatus("PROCESSING");
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
            var segments = documentSplitter.split(text, documentId.toString());
            log.info("Document {} split into {} chunks", documentId, segments.size());

            // 5. Embed all chunks
            var texts = segments.stream()
                    .map(TextSegment::text)
                    .toList();
            var vectors = embeddingService.embedBatch(texts);

            // 6. Store vectors in Milvus
            var vectorIds = vectorStore.storeBatch(vectors, segments);

            // 7. Save chunks to PG
            for (int i = 0; i < segments.size(); i++) {
                Chunk chunk = Chunk.builder()
                        .docId(documentId)
                        .content(segments.get(i).text())
                        .embeddingId(vectorIds.get(i))
                        .metadata("{\"index\": " + i + ", \"fileName\": \"" + message.getFileName() + "\"}")
                        .build();
                chunkMapper.insert(chunk);
            }

            // 8. Update document status
            doc.setStatus("COMPLETED");
            doc.setChunkCount(segments.size());
            documentMapper.updateById(doc);

            log.info("Document {} processed successfully: {} chars, {} chunks",
                    documentId, text.length(), segments.size());
        } catch (Exception e) {
            log.error("Error processing document {}: {}", documentId, e.getMessage(), e);
            doc.setStatus("FAILED");
            documentMapper.updateById(doc);
            throw e;
        }
    }
}
