package org.liar.zhiliao.vector;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Milvus 连接与建表配置，绑定 zhiliao.milvus.*。
 */
@Data
@ConfigurationProperties(prefix = "zhiliao.milvus")
public class MilvusProperties {

    private String host = "localhost";
    private int port = 19530;
    private String collectionName = "zhiliao_chunks";
    private String username;
    private String password;
    /** 向量维度；为 null 时取 EmbeddingModel.dimension() */
    private Integer dimension;
    /** 分区数：仅在存在分区键时可设，服务端默认 64，上限 4096 */
    private int numPartitions = 64;
}
