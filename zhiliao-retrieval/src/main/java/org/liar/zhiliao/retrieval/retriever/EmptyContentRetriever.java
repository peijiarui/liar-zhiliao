package org.liar.zhiliao.retrieval.retriever;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;

import java.util.Collections;
import java.util.List;

/**
 * 空内容检索器 — 始终返回空结果，用于路由到闲聊类查询时跳过 RAG。
 *
 * @author Pei
 * @since 2026-07-08
 */
public class EmptyContentRetriever implements ContentRetriever {

    @Override
    public List<Content> retrieve(Query query) {
        return Collections.emptyList();
    }
}
