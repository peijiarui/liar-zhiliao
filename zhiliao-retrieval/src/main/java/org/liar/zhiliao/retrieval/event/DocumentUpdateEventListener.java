package org.liar.zhiliao.retrieval.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.common.event.DocumentUpdateEvent;
import org.liar.zhiliao.retrieval.service.RetrievalCacheService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 文档更新事件监听器。
 * 知识库文档更新（新增/重新处理）时全量淘汰检索相关缓存，
 * 确保用户不会拿到过时的检索结果。
 *
 * 采用全量淘汰而非精准淘汰，因为文档更新频率低（每日1-3次），
 * 全量淘汰的代价远低于维护反向索引的复杂度。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentUpdateEventListener {

    private final RetrievalCacheService retrievalCacheService;

    @EventListener
    public void onDocumentUpdate(DocumentUpdateEvent event) {
        log.info("Document update event received, evicting all caches. docIds: {}", event.docIds());
        retrievalCacheService.evictAllRewrite();
        retrievalCacheService.evictAllRetrieval();
        log.info("Cache eviction completed for document update");
    }
}
