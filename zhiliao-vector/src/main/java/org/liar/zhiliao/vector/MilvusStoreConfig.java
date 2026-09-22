package org.liar.zhiliao.vector;

import dev.langchain4j.model.embedding.EmbeddingModel;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;

/**
 * 装配 Milvus 客户端与向量存储。启动期由 MilvusCollectionManager 建表或校验 schema。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(MilvusProperties.class)
public class MilvusStoreConfig {

    @Bean(destroyMethod = "close")
    public MilvusServiceClient milvusServiceClient(MilvusProperties properties) {
        return new MilvusServiceClient(ConnectParam.newBuilder()
                .withHost(properties.getHost())
                .withPort(properties.getPort())
                .withAuthorization(
                        properties.getUsername() == null ? "" : properties.getUsername(),
                        properties.getPassword() == null ? "" : properties.getPassword())
                .build());
    }

    @Bean
    public KbAwareEmbeddingStore milvusKbEmbeddingStore(MilvusServiceClient client,
                                                       MilvusProperties properties,
                                                       @Nullable EmbeddingModel embeddingModel) {
        Integer dimension = properties.getDimension() != null
                ? properties.getDimension()
                : (embeddingModel == null ? null : embeddingModel.dimension());
        if (dimension == null) {
            throw new IllegalStateException("Milvus vector dimension unknown: "
                    + "set zhiliao.milvus.dimension or provide an EmbeddingModel");
        }

        new MilvusCollectionManager(
                client, properties.getCollectionName(), dimension, properties.getNumPartitions()).ensure();

        log.info("Milvus store ready: collection={}, dimension={}, numPartitions={}",
                properties.getCollectionName(), dimension, properties.getNumPartitions());
        return new MilvusKbEmbeddingStore(client, properties.getCollectionName());
    }
}
