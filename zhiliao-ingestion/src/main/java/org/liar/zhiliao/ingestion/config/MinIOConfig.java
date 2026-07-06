package org.liar.zhiliao.ingestion.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinIOConfig {

    @Value("${zhiliao.minio.endpoint}")
    private String endpoint;

    @Value("${zhiliao.minio.access-key}")
    private String accessKey;

    @Value("${zhiliao.minio.secret-key}")
    private String secretKey;

    @Value("${zhiliao.minio.bucket}")
    private String bucket;

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    public String getBucket() {
        return bucket;
    }
}
