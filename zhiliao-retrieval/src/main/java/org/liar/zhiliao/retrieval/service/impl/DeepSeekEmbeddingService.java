//package org.liar.zhiliao.retrieval.service.impl;
//
//import dev.langchain4j.data.segment.TextSegment;
//import dev.langchain4j.model.embedding.EmbeddingModel;
//import dev.langchain4j.model.output.Response;
//import lombok.AllArgsConstructor;
//import org.liar.zhiliao.retrieval.service.EmbeddingService;
//import org.springframework.stereotype.Service;
//
//import java.util.List;
//
//@Service
//@AllArgsConstructor
//public class DeepSeekEmbeddingService implements EmbeddingService {
//
//    private final EmbeddingModel embeddingModel;
//
//    @Override
//    public float[] embed(String text) {
//        Response<dev.langchain4j.data.embedding.Embedding> response = embeddingModel.embed(text);
//        return response.content().vector();
//    }
//
//    @Override
//    public List<float[]> embedBatch(List<String> texts) {
//        List<TextSegment> segments = texts.stream()
//                .map(TextSegment::from)
//                .toList();
//        Response<List<dev.langchain4j.data.embedding.Embedding>> response = embeddingModel.embedAll(segments);
//        return response.content().stream()
//                .map(dev.langchain4j.data.embedding.Embedding::vector)
//                .toList();
//    }
//
//    @Override
//    public int dimension() {
//        // DeepSeek Embedding outputs 1024-dimensional vectors
//        return 1024;
//    }
//}
