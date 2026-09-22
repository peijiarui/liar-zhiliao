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

import java.io.ByteArrayInputStream;

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

    DocumentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DocumentServiceImpl(minioClient, minIOConfig, documentMapper,
                visibilityMapper, rabbitTemplate, chunkMapper, jdbcTemplate, transactionTemplate);
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
}
