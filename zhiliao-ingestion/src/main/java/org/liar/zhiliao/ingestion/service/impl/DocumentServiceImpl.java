package org.liar.zhiliao.ingestion.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.liar.zhiliao.common.mapper.ZlKbDeptVisibilityMapper;
import org.liar.zhiliao.ingestion.config.MinIOConfig;
import org.liar.zhiliao.ingestion.config.RabbitMQConfig;
import org.liar.zhiliao.ingestion.entity.ZlDocument;
import org.liar.zhiliao.ingestion.mapper.ZlDocumentMapper;
import org.liar.zhiliao.ingestion.model.DocumentMessage;
import org.liar.zhiliao.ingestion.service.DocumentService;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.liar.zhiliao.common.model.CurrentUser;
import org.liar.zhiliao.common.utils.UserContextHolder;
import org.liar.zhiliao.common.entity.ZlKbDeptVisibility;

/**
 * @author Pei
 * @since 2026-07-06
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {


    private final MinioClient minioClient;
    private final MinIOConfig minIOConfig;
    private final ZlDocumentMapper documentMapper;
    private final ZlKbDeptVisibilityMapper visibilityMapper;
    private final RabbitTemplate rabbitTemplate;

    public ZlDocument upload(MultipartFile file, Long kbId) {
        // 1. Compute MD5
        String fileMd5;
        try {
            fileMd5 = DigestUtils.md5Hex(file.getInputStream());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // 从当前登录用户获取部门
        CurrentUser currentUser = UserContextHolder.get();
        Long deptId = currentUser != null ? currentUser.deptId() : 1L;

        // 2. Generate MinIO key
        String minioKey = "docs/" + kbId + "/" + UUID.randomUUID() + "/" + file.getOriginalFilename();

        // 3. Save to MinIO
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(minIOConfig.getBucket())
                    .object(minioKey)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // 4. Create document record
        ZlDocument doc = ZlDocument.builder()
                .kbId(kbId)
                .fileName(file.getOriginalFilename())
                .fileType(file.getContentType())
                .status("UPLOADED")
                .minioKey(minioKey)
                .fileSize(file.getSize())
                .md5(fileMd5)
                .deptId(deptId)
                .build();
        documentMapper.insert(doc);

        // 确保知识库-部门可见性记录存在（供 BM25 检索过滤用）
        ZlKbDeptVisibility existing = visibilityMapper.selectOne(
                Wrappers.<ZlKbDeptVisibility>lambdaQuery()
                        .eq(ZlKbDeptVisibility::getKbId, kbId)
                        .eq(ZlKbDeptVisibility::getDeptId, deptId));
        if (existing == null) {
            ZlKbDeptVisibility visibility = ZlKbDeptVisibility.builder()
                    .kbId(kbId)
                    .deptId(deptId)
                    .build();
            visibilityMapper.insert(visibility);
        }

        // 5. Send to RabbitMQ for async processing
        DocumentMessage message = DocumentMessage.builder()
                .documentId(doc.getId())
                .minioKey(minioKey)
                .fileName(file.getOriginalFilename())
                .build();
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.ROUTING_KEY, message);
        log.info("Sent document {} to processing queue", doc.getId());

        return doc;
    }

    public ZlDocument getDocument(Long id) {
        return documentMapper.selectById(id);
    }

    @Override
    public List<ZlDocument> listDocuments(Long kbId, Integer page, Integer pageSize) {
        LambdaQueryWrapper<ZlDocument> wrapper = new LambdaQueryWrapper<ZlDocument>()
                .eq(kbId != null, ZlDocument::getKbId, kbId)
                .orderByDesc(ZlDocument::getCreatedAt);

        if (page != null && pageSize != null) {
            Page<ZlDocument> p = documentMapper.selectPage(new Page<>(page, pageSize), wrapper);
            return p.getRecords();
        }
        return documentMapper.selectList(wrapper);
    }

}
