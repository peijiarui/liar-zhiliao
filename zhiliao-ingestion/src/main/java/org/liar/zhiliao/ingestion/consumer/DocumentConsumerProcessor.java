package org.liar.zhiliao.ingestion.consumer;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.ingestion.config.MinIOConfig;
import org.liar.zhiliao.ingestion.entity.ZlChunk;
import org.liar.zhiliao.ingestion.entity.ZlDocument;
import org.liar.zhiliao.ingestion.enums.DocumentStatusEnum;
import org.liar.zhiliao.ingestion.mapper.ZlChunkMapper;
import org.liar.zhiliao.ingestion.mapper.ZlDocumentMapper;
import org.liar.zhiliao.ingestion.model.DocumentMessage;
import org.liar.zhiliao.ingestion.records.ParentChildSplitResult;
import org.liar.zhiliao.ingestion.service.DocumentParser;
import org.liar.zhiliao.ingestion.service.RecursiveDocumentSplitter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentConsumerProcessor {

    private final MinioClient minioClient;
    private final MinIOConfig minIOConfig;
    private final DocumentParser documentParser;
    private final RecursiveDocumentSplitter recursiveDocumentSplitter;
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

            // 4. 父子文档分割
            ParentChildSplitResult splitResult = recursiveDocumentSplitter.split(text, documentId.toString());
            log.info("Document {} split into {} parents, {} children", documentId,
                    splitResult.parentSegments().size(), splitResult.childSegments().size());

            // 5. 先写所有 parent 到 PG，获取自增 ID
            List<Long> parentIds = new ArrayList<>();
            for (TextSegment parentSeg : splitResult.parentSegments()) {
                ZlChunk parentChunk = ZlChunk.builder()
                        .docId(documentId)
                        .content(parentSeg.text())
                        .chunkType("parent")
                        .metadata("{\"fileName\": \"" + message.getFileName() + "\"}")
                        .build();
                chunkMapper.insert(parentChunk);
                parentIds.add(parentChunk.getId());
            }

            // 6. 写所有 child 到 PG（含 parent_id），同时收集需要 Embedding 的内容
            List<ZlChunk> childEntities = new ArrayList<>();
            List<TextSegment> childSegments = new ArrayList<>();
            int[] mapping = splitResult.childParentMapping();

            for (int i = 0; i < splitResult.childSegments().size(); i++) {
                TextSegment childSeg = splitResult.childSegments().get(i);
                ZlChunk childChunk = ZlChunk.builder()
                        .docId(documentId)
                        .content(childSeg.text())
                        .parentId(parentIds.get(mapping[i]))
                        .chunkType("child")
                        .metadata("{\"index\": " + i + ", \"fileName\": \"" + message.getFileName() + "\"}")
                        .build();
                chunkMapper.insert(childChunk);
                childEntities.add(childChunk);
                childSegments.add(childSeg);
            }

            // 7. 只对 child 做 Embedding 并写入 Milvus
            List<TextSegment> childSegmentsWithMeta = new ArrayList<>();
            for (int i = 0; i < childSegments.size(); i++) {
                ZlChunk childEntity = childEntities.get(i);
                TextSegment segWithMeta = TextSegment.from(
                        childSegments.get(i).text(),
                        Metadata.from("chunkId", childEntity.getId().toString())
                                .put("parentId", childEntity.getParentId() != null
                                        ? childEntity.getParentId().toString() : ""));
                childSegmentsWithMeta.add(segWithMeta);
            }

            List<Embedding> embeddings = embeddingModel.embedAll(childSegmentsWithMeta).content();
            List<String> vectorIds = milvusEmbeddingStore.addAll(embeddings, childSegmentsWithMeta);

            for (int i = 0; i < childEntities.size(); i++) {
                childEntities.get(i).setEmbeddingId(vectorIds.get(i));
                chunkMapper.updateById(childEntities.get(i));
            }

            // 8. 更新 document 状态（chunkCount = parent 数）
            doc.setStatus(DocumentStatusEnum.COMPLETED.getStatus());
            doc.setChunkCount(splitResult.parentSegments().size());
            documentMapper.updateById(doc);

            log.info("Document {} processed successfully: {} parents, {} children",
                    documentId, splitResult.parentSegments().size(), splitResult.childSegments().size());
        } catch (Exception e) {
            log.error("Error processing document {}: {}", documentId, e.getMessage(), e);
            doc.setStatus(DocumentStatusEnum.FAILED.getStatus());
            documentMapper.updateById(doc);
        }
    }
}
