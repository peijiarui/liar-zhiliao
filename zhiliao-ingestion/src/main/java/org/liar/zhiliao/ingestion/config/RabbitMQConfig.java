package org.liar.zhiliao.ingestion.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE = "zhiliao.direct";
    public static final String QUEUE = "zhiliao.document.process";
    public static final String ROUTING_KEY = "document.process";

    @Bean
    public DirectExchange documentExchange() {
        return new DirectExchange(EXCHANGE);
    }

    @Bean
    public Queue documentQueue() {
        return QueueBuilder.durable(QUEUE).build();
    }

    @Bean
    public Binding documentBinding(DirectExchange documentExchange, Queue documentQueue) {
        return BindingBuilder.bind(documentQueue)
                .to(documentExchange)
                .with(ROUTING_KEY);
    }

    // 新增：JSON消息转换器，替换默认SimpleMessageConverter
    @Bean
    public MessageConverter jackson2JsonMessageConverter(ObjectMapper objectMapper) {
        // 复用Spring容器自带的ObjectMapper，统一全局序列化规则
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
