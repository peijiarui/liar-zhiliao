package org.liar.zhiliao.retrieval.router;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.router.QueryRouter;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.retrieval.entity.SysRoutingSeed;
import org.liar.zhiliao.retrieval.mapper.SysRoutingSeedMapper;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Embedding 相似度的查询路由器。
 * <p>
 * 启动时从 {@code sys_routing_seed} 表加载 chat/knowledge 两类种子并计算质心 embedding；
 * 运行时将用户查询 embedding 分别与两个质心计算余弦相似度，
 * 若接近闲聊质心且超过阈值，路由到空检索器（跳过 RAG）；
 * 否则路由到知识库检索器。
 * <p>
 * 数据库为空时降级使用内置默认种子。
 *
 * @author Pei
 * @since 2026-07-08
 */
@Slf4j
public class EmbeddingQueryRouter implements QueryRouter {

    private static final double CHAT_THRESHOLD = 0.78;

    private static final String REDIS_KEY_CHAT = "routing:centroid:chat";
    private static final String REDIS_KEY_KNOWLEDGE = "routing:centroid:knowledge";
    private static final long REDIS_TTL_SECONDS = 7 * 24 * 60 * 60;

    private static final List<String> DEFAULT_CHAT_SEEDS = List.of(
            "你好", "您好", "嗨", "hello", "hi", "hey",
            "早上好", "下午好", "晚上好", "大家好",
            "再见", "拜拜", "bye", "回见", "明天见",
            "谢谢", "感谢", "多谢", "thanks", "thank you",
            "你是谁", "你叫什么", "who are you",
            "好的", "可以", "ok", "嗯", "行", "好滴", "没问题",
            "在吗", "在不在", "有人吗",
            "哈哈", "哈哈哈"
    );

    private static final List<String> DEFAULT_KNOWLEDGE_SEEDS = List.of(
            "什么是报销制度",
            "请假流程是什么",
            "公司的考勤规定",
            "如何在系统中提交申请",
            "费用报销的标准是什么",
            "介绍一下公司的产品",
            "具体的操作步骤",
            "where can I find the policy",
            "how to apply for leave",
            "有没有关于休假的规定",
            "差旅费怎么报销",
            "加班怎么申请"
    );

    private final EmbeddingModel embeddingModel;
    private final ContentRetriever knowledgeRetriever;
    private final ContentRetriever emptyRetriever;
    private final SysRoutingSeedMapper seedMapper;
    private final String embeddingBaseUrl;
    private final String embeddingModelName;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private volatile Embedding chatCentroid;
    private volatile Embedding knowledgeCentroid;

    public EmbeddingQueryRouter(EmbeddingModel embeddingModel,
                                ContentRetriever knowledgeRetriever,
                                ContentRetriever emptyRetriever,
                                SysRoutingSeedMapper seedMapper,
                                String embeddingBaseUrl,
                                String embeddingModelName,
                                StringRedisTemplate redisTemplate,
                                ObjectMapper objectMapper) {
        this.embeddingModel = embeddingModel;
        this.knowledgeRetriever = knowledgeRetriever;
        this.emptyRetriever = emptyRetriever;
        this.seedMapper = seedMapper;
        this.embeddingBaseUrl = embeddingBaseUrl;
        this.embeddingModelName = embeddingModelName;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 初始化时优先从 Redis 加载缓存的质心，若不存在则计算后缓存（7 天过期）。
     */
    public void init() {
        if (loadFromRedis()) {
            log.info("从 Redis 加载路由质心成功，跳过向量化");
            return;
        }

        log.info("Redis 无缓存质心，从种子计算...");
        List<String> chatSeeds = loadSeeds("chat");
        List<String> knowledgeSeeds = loadSeeds("knowledge");

        if (chatSeeds.isEmpty()) {
            log.warn("sys_routing_seed 表无 chat 种子，降级到内置默认值 ({} 条)", DEFAULT_CHAT_SEEDS.size());
            chatSeeds = DEFAULT_CHAT_SEEDS;
        }
        if (knowledgeSeeds.isEmpty()) {
            log.warn("sys_routing_seed 表无 knowledge 种子，降级到内置默认值 ({} 条)", DEFAULT_KNOWLEDGE_SEEDS.size());
            knowledgeSeeds = DEFAULT_KNOWLEDGE_SEEDS;
        }

        log.info("EmbeddingQueryRouter 计算质心: chatSeeds={}, knowledgeSeeds={}",
                chatSeeds.size(), knowledgeSeeds.size());
        this.chatCentroid = averageEmbeddings(chatSeeds);
        this.knowledgeCentroid = averageEmbeddings(knowledgeSeeds);
        saveToRedis();
        log.info("EmbeddingQueryRouter 初始化完成，质心已缓存到 Redis（7 天）");
    }

    /**
     * 从数据库加载指定类别的种子文本。
     */
    private List<String> loadSeeds(String category) {
        try {
            List<SysRoutingSeed> seeds = seedMapper.selectList(
                    Wrappers.<SysRoutingSeed>lambdaQuery()
                            .eq(SysRoutingSeed::getCategory, category)
                            .orderByAsc(SysRoutingSeed::getSortOrder));
            return seeds.stream().map(SysRoutingSeed::getContent).toList();
        } catch (Exception e) {
            log.warn("从数据库加载 [{}] 种子失败，将使用默认值: {}", category, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 从 Redis 加载缓存的质心向量，反序列化为 Embedding 对象。
     */
    private boolean loadFromRedis() {
        try {
            String chatJson = redisTemplate.opsForValue().get(REDIS_KEY_CHAT);
            String knowledgeJson = redisTemplate.opsForValue().get(REDIS_KEY_KNOWLEDGE);
            if (chatJson == null || knowledgeJson == null) {
                return false;
            }
            float[] chatVec = objectMapper.readValue(chatJson, float[].class);
            float[] knowledgeVec = objectMapper.readValue(knowledgeJson, float[].class);
            this.chatCentroid = new Embedding(chatVec);
            this.knowledgeCentroid = new Embedding(knowledgeVec);
            return true;
        } catch (Exception e) {
            log.warn("从 Redis 加载质心失败，将重新计算", e);
            return false;
        }
    }

    /**
     * 将质心向量序列化后存入 Redis，过期时间 7 天。
     */
    private void saveToRedis() {
        try {
            String chatJson = objectMapper.writeValueAsString(chatCentroid.vector());
            String knowledgeJson = objectMapper.writeValueAsString(knowledgeCentroid.vector());
            redisTemplate.opsForValue().set(REDIS_KEY_CHAT, chatJson, REDIS_TTL_SECONDS, TimeUnit.SECONDS);
            redisTemplate.opsForValue().set(REDIS_KEY_KNOWLEDGE, knowledgeJson, REDIS_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("保存质心到 Redis 失败", e);
        }
    }

    @Override
    public List<ContentRetriever> route(Query query) {
        String text = query.text().trim();

        if (text.isEmpty()) {
            return List.of(emptyRetriever);
        }

        log.debug("========开始调用向量模型========");
        log.debug("base-url : {}", embeddingBaseUrl);
        log.debug("model-name : {}", embeddingModelName);
        log.debug("text : {}", text);

        Embedding queryEmbedding = embeddingModel.embed(text).content();
        double simToChat = cosineSimilarity(queryEmbedding, chatCentroid);
        double simToKnowledge = cosineSimilarity(queryEmbedding, knowledgeCentroid);

        log.debug("路由决策: query='{}', simToChat={}, simToKnowledge={}",
                text, String.format("%.4f", simToChat), String.format("%.4f", simToKnowledge));

        if (simToChat > CHAT_THRESHOLD && simToChat > simToKnowledge) {
            log.debug("→ 路由到闲聊: 跳过 RAG");
            return List.of(emptyRetriever);
        }

        log.debug("→ 路由到知识检索");
        return List.of(knowledgeRetriever);
    }

    private Embedding averageEmbeddings(List<String> texts) {
        List<Embedding> embeddings = texts.stream()
                .map(text -> embeddingModel.embed(text).content())
                .toList();
        return average(embeddings);
    }

    private static Embedding average(List<Embedding> embeddings) {
        float[] sum = new float[embeddings.get(0).vector().length];
        for (Embedding emb : embeddings) {
            float[] vec = emb.vector();
            for (int i = 0; i < sum.length; i++) {
                sum[i] += vec[i];
            }
        }
        for (int i = 0; i < sum.length; i++) {
            sum[i] /= embeddings.size();
        }
        return new Embedding(sum);
    }

    private static double cosineSimilarity(Embedding a, Embedding b) {
        float[] vecA = a.vector();
        float[] vecB = b.vector();
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < vecA.length; i++) {
            dot += (double) vecA[i] * vecB[i];
            normA += (double) vecA[i] * vecA[i];
            normB += (double) vecB[i] * vecB[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
