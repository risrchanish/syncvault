package com.syncvault.fileservice.kafka;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FileEventProducer {

    private static final Logger log = LoggerFactory.getLogger(FileEventProducer.class);

    public static final String TOPIC_UPLOADED = "file.uploaded";
    public static final String TOPIC_UPDATED  = "file.updated";
    public static final String TOPIC_DELETED  = "file.deleted";
    public static final String TOPIC_CONFLICT = "file.conflict";

    private final KafkaTemplate<String, FileEvent> kafkaTemplate;

    public void publishUploaded(FileEvent event) {
        send(TOPIC_UPLOADED, event);
    }

    public void publishUpdated(FileEvent event) {
        send(TOPIC_UPDATED, event);
    }

    public void publishDeleted(FileEvent event) {
        send(TOPIC_DELETED, event);
    }

    public void publishConflict(FileEvent event) {
        send(TOPIC_CONFLICT, event);
    }

    private void send(String topic, FileEvent event) {
        kafkaTemplate.send(topic, event.getFileId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.warn("Failed to send event {} to topic {}: {}", event.getEventType(), topic, ex.getMessage());
                    } else {
                        log.debug("Sent event {} to topic {}", event.getEventType(), topic);
                    }
                });
    }
}
