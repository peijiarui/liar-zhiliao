package org.liar.zhiliao.ingestion.service.impl;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.ingestion.config.MinIOConfig;
import org.liar.zhiliao.ingestion.config.RabbitMQConfig;
import org.liar.zhiliao.ingestion.model.Document;
import org.liar.zhiliao.ingestion.repository.DocumentMapper;
import org.liar.zhiliao.ingestion.service.DocumentService;
import org.liar.zhiliao.ingestion.service.async.DocumentMessage;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

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
    private final DocumentMapper documentMapper;
    private final RabbitTemplate rabbitTemplate;

    public Document upload(MultipartFile file, Long kbId) {
        // 1. Compute MD5
        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        String md5;
        try {
            md5 = HexFormat.of().formatHex(
                    MessageDigest.getInstance("MD5").digest(fileBytes));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

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
        Document doc = Document.builder()
                .kbId(kbId)
                .fileName(file.getOriginalFilename())
                .fileType(file.getContentType())
                .status("UPLOADED")
                .minioKey(minioKey)
                .fileSize(file.getSize())
                .md5(md5)
                .build();
        documentMapper.insert(doc);

        // 5. Send to RabbitMQ for async processing
        DocumentMessage message = new DocumentMessage(doc.getId(), minioKey, file.getOriginalFilename());
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.ROUTING_KEY, message);
        log.info("Sent document {} to processing queue", doc.getId());

        return doc;
    }

    public Document getDocument(Long id) {
        return Optional.ofNullable(documentMapper.selectById(id))
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + id));
    }

}
