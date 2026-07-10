package org.liar.zhiliao.ingestion.config;

import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author liar
 * @since 11/07/26
 */
@Configuration
public class SpringBeanConfig {

    @Bean
    public DocumentSplitter recursiveDocumentSplitter(
            @Value("${rag.splitter.max-segment-size:500}") int maxSize,
            @Value("${rag.splitter.overlap-size:100}") int overlap) {
        return DocumentSplitters.recursive(maxSize, overlap);
    }

}
