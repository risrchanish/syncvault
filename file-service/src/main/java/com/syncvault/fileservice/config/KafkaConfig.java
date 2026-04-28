package com.syncvault.fileservice.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic fileUploadedTopic() {
        return TopicBuilder.name("file.uploaded").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic fileUpdatedTopic() {
        return TopicBuilder.name("file.updated").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic fileDeletedTopic() {
        return TopicBuilder.name("file.deleted").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic fileConflictTopic() {
        return TopicBuilder.name("file.conflict").partitions(3).replicas(1).build();
    }
}
