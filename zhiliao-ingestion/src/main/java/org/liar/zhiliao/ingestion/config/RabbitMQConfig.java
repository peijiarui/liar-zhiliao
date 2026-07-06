package org.liar.zhiliao.ingestion.config;

import org.springframework.amqp.core.*;
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
}
