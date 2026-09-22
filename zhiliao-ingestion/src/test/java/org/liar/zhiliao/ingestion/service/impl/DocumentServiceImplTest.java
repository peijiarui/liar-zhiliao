package org.liar.zhiliao.ingestion.service.impl;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.liar.zhiliao.common.entity.ZlKbDeptVisibility;
import org.liar.zhiliao.common.exception.BusinessException;
import org.liar.zhiliao.common.mapper.ZlKbDeptVisibilityMapper;
import org.liar.zhiliao.ingestion.config.MinIOConfig;
import org.liar.zhiliao.ingestion.config.RabbitMQConfig;
import org.liar.zhiliao.ingestion.entity.ZlDocument;
import org.liar.zhiliao.ingestion.mapper.ZlChunkMapper;
import org.liar.zhiliao.ingestion.mapper.ZlDocumentMapper;
import org.liar.zhiliao.ingestion.model.DocumentMessage;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentServiceImplTest {

    @Mock MinioClient minioClient;
    @Mock MinIOConfig minIOConfig;
    @Mock ZlDocumentMapper documentMapper;
    @Mock ZlKbDeptVisibilityMapper visibilityMapper;
    @Mock RabbitTemplate rabbitTemplate;
    @Mock ZlChunkMapper chunkMapper;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock TransactionTemplate transactionTemplate;
    @Mock dev.langchain4j.store.embedding.EmbeddingStore<dev.langchain4j.data.segment.TextSegment> milvusEmbeddingStore;
    @Mock org.springframework.context.ApplicationEventPublisher eventPublisher;

    DocumentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DocumentServiceImpl(minioClient, minIOConfig, documentMapper,
                visibilityMapper, rabbitTemplate, chunkMapper, jdbcTemplate, transactionTemplate,
                milvusEmbeddingStore, eventPublisher);
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes());
    }

    @Test
    void uploadShouldRejectNullKbId() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.upload(file(), null));
        assertEquals(400, ex.getStatus());
        verifyNoInteractions(minioClient, rabbitTemplate);
    }

    @Test
    void uploadShouldRejectUnknownKbId() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq(9L))).thenReturn(0L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.upload(file(), 9L));

        assertEquals(400, ex.getStatus());
        assertTrue(ex.getMessage().contains("知识库不存在"));
        verifyNoInteractions(minioClient, rabbitTemplate);
    }

    @Test
    void uploadShouldSaveToMinioInsertDocAndSendMq() throws Exception {
        when(minIOConfig.getBucket()).thenReturn("zhiliao");
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq(1L))).thenReturn(1L);
        when(documentMapper.insert(any(ZlDocument.class))).thenAnswer(i -> {
            ZlDocument d = i.getArgument(0);
            d.setId(100L);
            return 1;
        });
        when(visibilityMapper.selectOne(any())).thenReturn(null);
        // 上传时无登录上下文（UserContextHolder 为空），deptId 兜底 1L
        doAnswer(inv -> {
            PutObjectArgs args = inv.getArgument(0);
            assertNotNull(args);
            return null;
        }).when(minioClient).putObject(any(PutObjectArgs.class));

        ZlDocument doc = service.upload(file(), 1L);

        assertEquals(100L, doc.getId());
        assertEquals(1L, doc.getKbId());
        assertEquals("UPLOADED", doc.getStatus());
        verify(minioClient).putObject(any(PutObjectArgs.class));
        verify(visibilityMapper).insert(any(ZlKbDeptVisibility.class));
        ArgumentCaptor<DocumentMessage> msg = ArgumentCaptor.forClass(DocumentMessage.class);
        verify(rabbitTemplate).convertAndSend(eq(RabbitMQConfig.EXCHANGE), eq(RabbitMQConfig.ROUTING_KEY), msg.capture());
        assertEquals(100L, msg.getValue().getDocumentId());
    }

    @Test
    void deleteShouldThrow404WhenDocumentMissing() {
        when(documentMapper.selectById(404L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.delete(404L));

        assertEquals(404, ex.getStatus());
        verifyNoInteractions(chunkMapper, transactionTemplate);
    }

    @Test
    void deleteShouldAbortWhenMilvusRemoveFails() {
        org.liar.zhiliao.ingestion.entity.ZlDocument doc =
                org.liar.zhiliao.ingestion.entity.ZlDocument.builder()
                        .id(1L).minioKey("docs/1/x/a.txt").kbId(1L).build();
        when(documentMapper.selectById(1L)).thenReturn(doc);
        org.liar.zhiliao.ingestion.entity.ZlChunk child =
                org.liar.zhiliao.ingestion.entity.ZlChunk.builder()
                        .id(11L).docId(1L).chunkType("child").embeddingId("v-11").build();
        when(chunkMapper.selectList(any())).thenReturn(List.of(child));
        doThrow(new RuntimeException("milvus down"))
                .when(milvusEmbeddingStore).removeAll(anyCollection());

        assertThrows(RuntimeException.class, () -> service.delete(1L));

        verify(documentMapper, never()).deleteById(anyLong());
        verify(chunkMapper, never()).delete(any());
    }

    @Test
    void deleteShouldRemoveVectorsPgMinioAndPublishEvent() throws Exception {
        org.liar.zhiliao.ingestion.entity.ZlDocument doc =
                org.liar.zhiliao.ingestion.entity.ZlDocument.builder()
                        .id(1L).minioKey("docs/1/x/a.txt").kbId(1L).build();
        when(documentMapper.selectById(1L)).thenReturn(doc);
        org.liar.zhiliao.ingestion.entity.ZlChunk child =
                org.liar.zhiliao.ingestion.entity.ZlChunk.builder()
                        .id(11L).docId(1L).chunkType("child").embeddingId("v-11").build();
        org.liar.zhiliao.ingestion.entity.ZlChunk parent =
                org.liar.zhiliao.ingestion.entity.ZlChunk.builder()
                        .id(10L).docId(1L).chunkType("parent").embeddingId(null).build();
        when(chunkMapper.selectList(any())).thenReturn(List.of(child, parent));
        // executeWithoutResult 实际接收 Consumer<TransactionStatus>，非 Runnable
        doAnswer(inv -> {
            ((java.util.function.Consumer<?>) inv.getArgument(0)).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        // MinIO 8.5.17 builder 校验 bucket 非空，getBucket() 必须打桩
        when(minIOConfig.getBucket()).thenReturn("zhiliao");

        service.delete(1L);

        verify(milvusEmbeddingStore).removeAll(eq(List.of("v-11")));
        verify(chunkMapper).delete(any());
        verify(documentMapper).deleteById(1L);
        verify(minioClient).removeObject(any(io.minio.RemoveObjectArgs.class));
        ArgumentCaptor<org.liar.zhiliao.common.event.DocumentUpdateEvent> ev =
                ArgumentCaptor.forClass(org.liar.zhiliao.common.event.DocumentUpdateEvent.class);
        verify(eventPublisher).publishEvent(ev.capture());
        assertTrue(ev.getValue().docIds().contains(1L));
    }

    @Test
    void deleteShouldNotFailWhenMinioDeleteThrows() throws Exception {
        org.liar.zhiliao.ingestion.entity.ZlDocument doc =
                org.liar.zhiliao.ingestion.entity.ZlDocument.builder()
                        .id(2L).minioKey("docs/1/x/b.txt").kbId(1L).build();
        when(documentMapper.selectById(2L)).thenReturn(doc);
        when(chunkMapper.selectList(any())).thenReturn(List.of());
        // executeWithoutResult 实际接收 Consumer<TransactionStatus>，非 Runnable
        doAnswer(inv -> { ((java.util.function.Consumer<?>) inv.getArgument(0)).accept(null); return null; })
                .when(transactionTemplate).executeWithoutResult(any());
        // MinIO 8.5.17 builder 校验 bucket 非空，getBucket() 必须打桩
        when(minIOConfig.getBucket()).thenReturn("zhiliao");
        org.mockito.Mockito.doThrow(new RuntimeException("minio down"))
                .when(minioClient).removeObject(any(io.minio.RemoveObjectArgs.class));

        assertDoesNotThrow(() -> service.delete(2L));

        verify(documentMapper).deleteById(2L);
        verify(eventPublisher).publishEvent(any(org.liar.zhiliao.common.event.DocumentUpdateEvent.class));
    }

    @Test
    void reprocessShouldResetStatusAndSendMq() {
        org.liar.zhiliao.ingestion.entity.ZlDocument doc =
                org.liar.zhiliao.ingestion.entity.ZlDocument.builder()
                        .id(1L).minioKey("docs/1/x/a.txt").fileName("a.txt").build();
        when(documentMapper.selectById(1L)).thenReturn(doc);

        service.reprocess(1L);

        assertEquals("UPLOADED", doc.getStatus());
        verify(documentMapper).updateById(doc);
        ArgumentCaptor<DocumentMessage> msg = ArgumentCaptor.forClass(DocumentMessage.class);
        verify(rabbitTemplate).convertAndSend(eq(RabbitMQConfig.EXCHANGE), eq(RabbitMQConfig.ROUTING_KEY), msg.capture());
        assertEquals("docs/1/x/a.txt", msg.getValue().getMinioKey());
    }

    @Test
    void reprocessShouldIgnoreMissingDocument() {
        when(documentMapper.selectById(99L)).thenReturn(null);
        assertDoesNotThrow(() -> service.reprocess(99L));
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void getDocumentShouldThrow404WhenMissing() {
        when(documentMapper.selectById(404L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.getDocument(404L));
        assertEquals(404, ex.getStatus());
    }
}
