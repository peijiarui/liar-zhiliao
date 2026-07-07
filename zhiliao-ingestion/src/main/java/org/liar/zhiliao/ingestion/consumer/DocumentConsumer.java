package org.liar.zhiliao.ingestion.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.ingestion.model.DocumentMessage;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentConsumer {

    private final DocumentConsumerProcessor processor;

    @RabbitListener(queues = "${zhiliao.rabbitmq.queue:zhiliao.document.process}")
    public void handleDocumentProcessing(DocumentMessage message) {
        log.info("Received document processing message: documentId={}, fileName={}",
                message.getDocumentId(), message.getFileName());
        try {
            processor.process(message);
        } catch (Exception e) {
            log.error("Failed to process document {}: {}", message.getDocumentId(), e.getMessage(), e);
        }
    }
}
