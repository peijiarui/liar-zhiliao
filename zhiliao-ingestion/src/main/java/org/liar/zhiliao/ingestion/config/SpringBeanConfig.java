package org.liar.zhiliao.ingestion.config;

import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author liar
 * @since 11/07/26
 */
@Configuration
public class SpringBeanConfig {

    @Bean
    public DocumentSplitter parentSplitter() {
        return DocumentSplitters.recursive(2048, 200);
    }

    @Bean
    public DocumentSplitter childSplitter() {
        return DocumentSplitters.recursive(512, 50);
    }

}
