package org.liar.zhiliao.admin.vo;

/**
 * 数据看板响应 VO。
 *
 * @param totalUsers           用户总数
 * @param totalConversations   对话总数
 * @param todayConversations   今日对话数
 * @param todayActiveUsers     今日活跃用户数
 * @param totalDocuments       文档总数
 * @param totalChunks          切片总数
 * @param processingDocuments  处理中的文档数
 * @param failedDocuments      处理失败的文档数
 * @param knowledgeBases       知识库总数
 * @param avgResponseTime      平均响应时间（秒）
 * @param p95ResponseTime      P95 响应时间（秒）
 * @param cacheHitRate         缓存命中率（0.0 ~ 1.0）
 * @param emptyResultRate      空结果率（0.0 ~ 1.0）
 */
public record DashboardVO(
        long totalUsers,
        long totalConversations,
        long todayConversations,
        long todayActiveUsers,
        long totalDocuments,
        long totalChunks,
        long processingDocuments,
        long failedDocuments,
        long knowledgeBases,
        double avgResponseTime,
        double p95ResponseTime,
        double cacheHitRate,
        double emptyResultRate
) {}
