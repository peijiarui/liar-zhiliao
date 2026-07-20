package org.liar.zhiliao.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.liar.zhiliao.admin.mapper.ZlKnowledgeBaseMapper;
import org.liar.zhiliao.admin.service.DashboardService;
import org.liar.zhiliao.admin.vo.DashboardVO;
import org.liar.zhiliao.auth.entity.SysUser;
import org.liar.zhiliao.auth.mapper.SysUserMapper;
import org.liar.zhiliao.chat.entity.Conversation;
import org.liar.zhiliao.chat.mapper.ConversationMapper;
import org.liar.zhiliao.ingestion.entity.ZlChunk;
import org.liar.zhiliao.ingestion.entity.ZlDocument;
import org.liar.zhiliao.ingestion.enums.DocumentStatusEnum;
import org.liar.zhiliao.ingestion.mapper.ZlChunkMapper;
import org.liar.zhiliao.ingestion.mapper.ZlDocumentMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 数据看板服务实现。
 * 从 PostgreSQL 各表统计汇总，性能数据从 Prometheus 查（当前用静态值占位）。
 */
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private final SysUserMapper userMapper;
    private final ConversationMapper conversationMapper;
    private final ZlDocumentMapper documentMapper;
    private final ZlChunkMapper chunkMapper;
    private final ZlKnowledgeBaseMapper knowledgeBaseMapper;

    @Override
    public DashboardVO getStats() {
        long totalUsers = userMapper.selectCount(null);
        long totalConversations = conversationMapper.selectCount(null);
        long totalDocuments = documentMapper.selectCount(null);
        long totalChunks = chunkMapper.selectCount(null);
        long knowledgeBases = knowledgeBaseMapper.selectCount(null);

        // 今日数据
        OffsetDateTime todayStart = LocalDate.now().atStartOfDay().atOffset(ZoneOffset.ofHours(8));
        long todayConversations = conversationMapper.selectCount(
                new LambdaQueryWrapper<Conversation>()
                        .ge(Conversation::getCreatedAt, todayStart));

        // 处理中的文档
        long processingDocuments = documentMapper.selectCount(
                new LambdaQueryWrapper<ZlDocument>()
                        .eq(ZlDocument::getStatus, DocumentStatusEnum.PROCESSING.getStatus()));
        long failedDocuments = documentMapper.selectCount(
                new LambdaQueryWrapper<ZlDocument>()
                        .eq(ZlDocument::getStatus, DocumentStatusEnum.FAILED.getStatus()));

        // 今日活跃用户（今日有对话记录的用户数）
        long todayActiveUsers = conversationMapper.selectCount(
                new LambdaQueryWrapper<Conversation>()
                        .ge(Conversation::getCreatedAt, todayStart)
                        .isNotNull(Conversation::getUserId)
                        .select(Conversation::getUserId));

        // TODO: 从 Prometheus 查询 avgResponseTime / p95ResponseTime / cacheHitRate
        return new DashboardVO(
                totalUsers, totalConversations, todayConversations, todayActiveUsers,
                totalDocuments, totalChunks, processingDocuments, failedDocuments, knowledgeBases,
                0.0, 0.0, 0.0, 0.0
        );
    }
}
