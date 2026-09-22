package org.liar.zhiliao.ingestion.consumer;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.liar.zhiliao.common.event.DocumentUpdateEvent;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 重处理幂等化测试：process 前清理既有切片与向量，首次处理时清理为无害 no-op。
 */
@ExtendWith(MockitoExtension.class)
class DocumentConsumerProcessorTest {

    @Mock MinioClient minioClient;
    @Mock MinIOConfig minIOConfig;
    @Mock DocumentParser documentParser;
    @Mock RecursiveDocumentSplitter recursiveDocumentSplitter;
    @Mock ZlDocumentMapper documentMapper;
    @Mock ZlChunkMapper chunkMapper;
    @Mock EmbeddingModel embeddingModel;
    @Mock EmbeddingStore<TextSegment> milvusEmbeddingStore;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock GetObjectResponse minioObject;

    DocumentConsumerProcessor processor;
    ZlDocument doc;

    @BeforeEach
    void setUp() {
        processor = new DocumentConsumerProcessor(minioClient, minIOConfig, documentParser,
                recursiveDocumentSplitter, documentMapper, chunkMapper, embeddingModel,
                milvusEmbeddingStore, eventPublisher);
        doc = ZlDocument.builder().id(1L).kbId(1L).deptId(2L).minioKey("docs/1/x/a.txt").build();
        when(documentMapper.selectById(1L)).thenReturn(doc);
    }

    private void stubHappyPath() throws Exception {
        // MinIO 8.5.17 builder 校验 bucket 非空，getBucket() 必须打桩
        when(minIOConfig.getBucket()).thenReturn("zhiliao");
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(minioObject);
        when(documentParser.parse(any(InputStream.class), anyString())).thenReturn("text");
        when(recursiveDocumentSplitter.split(anyString(), anyString())).thenReturn(
                new ParentChildSplitResult(List.of(TextSegment.from("parent")),
                        List.of(TextSegment.from("child")), new int[]{0}));
        AtomicLong seq = new AtomicLong(100);
        when(chunkMapper.insert(any(ZlChunk.class))).thenAnswer(inv -> {
            ((ZlChunk) inv.getArgument(0)).setId(seq.incrementAndGet());
            return 1;
        });
        when(embeddingModel.embedAll(anyList()))
                .thenReturn(Response.from(List.of(Embedding.from(new float[]{0.1f}))));
        when(milvusEmbeddingStore.addAll(anyList(), anyList())).thenReturn(List.of("v-new"));
    }

    private DocumentMessage message() {
        return DocumentMessage.builder().documentId(1L).minioKey("docs/1/x/a.txt").fileName("a.txt").build();
    }

    @Test
    void processShouldRemoveStaleChunksAndVectorsBeforeInsert() throws Exception {
        stubHappyPath();
        // 既有旧数据：旧 child 带 embeddingId（重处理场景）
        ZlChunk staleChild = ZlChunk.builder()
                .id(11L).docId(1L).chunkType("child").embeddingId("v-old").build();
        when(chunkMapper.selectList(any())).thenReturn(List.of(staleChild));

        processor.process(message());

        // 旧 embeddingId 被 removeAll
        verify(milvusEmbeddingStore).removeAll(eq(List.of("v-old")));
        // 旧 chunk 行被删
        verify(chunkMapper).delete(any());
        // 新数据仍写入：1 parent + 1 child insert，child 向量写 Milvus 并回填 embeddingId
        verify(chunkMapper, times(2)).insert(any(ZlChunk.class));
        verify(milvusEmbeddingStore).addAll(anyList(), anyList());
        ArgumentCaptor<ZlChunk> updated = ArgumentCaptor.forClass(ZlChunk.class);
        verify(chunkMapper).updateById(updated.capture());
        assertEquals("v-new", updated.getValue().getEmbeddingId());
        assertEquals(DocumentStatusEnum.COMPLETED.getStatus(), doc.getStatus());
        verify(eventPublisher).publishEvent(any(DocumentUpdateEvent.class));
    }

    @Test
    void processShouldNoOpCleanupOnFirstProcessing() throws Exception {
        stubHappyPath();
        // 首次处理：无旧数据
        when(chunkMapper.selectList(any())).thenReturn(List.of());

        processor.process(message());

        // 清理为无害 no-op：不删向量、不删 chunk 行
        verify(milvusEmbeddingStore, never()).removeAll(anyCollection());
        verify(chunkMapper, never()).delete(any());
        verify(chunkMapper, times(2)).insert(any(ZlChunk.class));
        verify(milvusEmbeddingStore).addAll(anyList(), anyList());
        assertEquals(DocumentStatusEnum.COMPLETED.getStatus(), doc.getStatus());
    }
}
