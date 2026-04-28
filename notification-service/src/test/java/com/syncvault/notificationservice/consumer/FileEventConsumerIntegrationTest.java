package com.syncvault.notificationservice.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncvault.notificationservice.client.UserServiceClient;
import com.syncvault.notificationservice.dto.FileEvent;
import com.syncvault.notificationservice.repository.ProcessedEventRepository;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@EmbeddedKafka(
    partitions = 1,
    bootstrapServersProperty = "spring.kafka.bootstrap-servers",
    topics = {"file.uploaded", "file.updated", "file.deleted", "file.conflict"}
)
@ActiveProfiles("test")
@DirtiesContext
class FileEventConsumerIntegrationTest {

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private JavaMailSender mailSender;

    @MockitoBean
    private UserServiceClient userServiceClient;

    private KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void setUp() {
        processedEventRepository.deleteAll();

        Map<String, Object> producerProps = new HashMap<>();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafkaBroker.getBrokersAsString());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        kafkaTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProps));
    }

    @Test
    void fileUploaded_sendsEmailAndStoresEventId() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(inv -> { latch.countDown(); return null; }).when(mailSender).send(any(SimpleMailMessage.class));

        FileEvent event = buildEvent("file.uploaded", "report.pdf");
        kafkaTemplate.send("file.uploaded", objectMapper.writeValueAsString(event));

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
        assertThat(processedEventRepository.existsById(event.getEventId())).isTrue();
    }

    @Test
    void fileUpdated_sendsEmail() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(inv -> { latch.countDown(); return null; }).when(mailSender).send(any(SimpleMailMessage.class));

        FileEvent event = buildEvent("file.updated", "notes.txt");
        kafkaTemplate.send("file.updated", objectMapper.writeValueAsString(event));

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    }

    @Test
    void fileDeleted_sendsEmail() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(inv -> { latch.countDown(); return null; }).when(mailSender).send(any(SimpleMailMessage.class));

        FileEvent event = buildEvent("file.deleted", "old-doc.docx");
        kafkaTemplate.send("file.deleted", objectMapper.writeValueAsString(event));

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    }

    @Test
    void fileConflict_sendsEmail() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(inv -> { latch.countDown(); return null; }).when(mailSender).send(any(SimpleMailMessage.class));

        FileEvent event = buildEvent("file.conflict", "budget.xlsx");
        kafkaTemplate.send("file.conflict", objectMapper.writeValueAsString(event));

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    }

    @Test
    void duplicateEvent_emailSentOnlyOnce() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(inv -> { latch.countDown(); return null; }).when(mailSender).send(any(SimpleMailMessage.class));

        FileEvent event = buildEvent("file.uploaded", "report.pdf");
        String json = objectMapper.writeValueAsString(event);

        kafkaTemplate.send("file.uploaded", json);
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

        // Send exact same event a second time
        kafkaTemplate.send("file.uploaded", json);
        Thread.sleep(2000);  // allow time for second message to be consumed

        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    }

    @Test
    void unknownEventType_noEmailSent() throws Exception {
        FileEvent event = buildEvent("file.unknown_type", "test.txt");
        kafkaTemplate.send("file.uploaded", objectMapper.writeValueAsString(event));

        Thread.sleep(2000);
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    private FileEvent buildEvent(String eventType, String fileName) {
        return new FileEvent(
                UUID.randomUUID().toString(),
                eventType,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                fileName,
                1024L,
                "2025-01-01T10:00:00Z",
                null
        );
    }
}
