//package org.liar.zhiliao.retrieval.retriever;
//
//import dev.langchain4j.data.embedding.Embedding;
//import dev.langchain4j.data.segment.TextSegment;
//import dev.langchain4j.model.embedding.EmbeddingModel;
//import dev.langchain4j.model.output.Response;
//import lombok.extern.slf4j.Slf4j;
//
//import java.util.List;
//import java.util.concurrent.ConcurrentHashMap;
//
///**
// * 带简单缓存的 {@link EmbeddingModel} 装饰器。
// * <p>
// * 解决 {@code EmbeddingQueryRouter} 和 {@code CustomContentRetriever} 在同一请求中对同一段文本
// * 重复调用向量模型的问题。内部使用 {@link ConcurrentHashMap}，超出 {@link #MAX_CACHE_SIZE} 时自动清空。
// */
//@Slf4j
//public class CachedEmbeddingModel implements EmbeddingModel {
//
//    private static final int MAX_CACHE_SIZE = 2048;
//
//    private final EmbeddingModel delegate;
//    private final ConcurrentHashMap<String, Response<Embedding>> cache = new ConcurrentHashMap<>();
//
//    public CachedEmbeddingModel(EmbeddingModel delegate) {
//        this.delegate = delegate;
//    }
//
//    @Override
//    public Response<Embedding> embed(String text) {
//        if (cache.size() > MAX_CACHE_SIZE) {
//            cache.clear();
//        }
//        Response<Embedding> result = cache.get(text);
//        if (result != null) {
//            log.debug("CachedEmbeddingModel hit for text='{}'", text);
//            return result;
//        }
//        log.debug("CachedEmbeddingModel miss for text='{}'", text);
//        result = delegate.embed(text);
//        cache.put(text, result);
//        return result;
//    }
//
//    @Override
//    public Response<Embedding> embed(TextSegment textSegment) {
//        return embed(textSegment.text());
//    }
//
//    @Override
//    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
//        return delegate.embedAll(textSegments);
//    }
//
//    @Override
//    public int dimension() {
//        return delegate.dimension();
//    }
//
//    @Override
//    public String modelName() {
//        return delegate.modelName();
//    }
//}
