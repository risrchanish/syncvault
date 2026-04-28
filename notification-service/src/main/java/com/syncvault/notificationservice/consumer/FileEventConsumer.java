package com.syncvault.notificationservice.consumer;

import com.syncvault.notificationservice.client.UserServiceClient;
import com.syncvault.notificationservice.dto.FileEvent;
import com.syncvault.notificationservice.entity.ProcessedEvent;
import com.syncvault.notificationservice.repository.ProcessedEventRepository;
import com.syncvault.notificationservice.service.EmailNotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Slf4j
@Component
public class FileEventConsumer {

    private final EmailNotificationService emailService;
    private final ProcessedEventRepository processedEventRepository;
    private final UserServiceClient userServiceClient;

    @Value("${notification.dev-recipient:}")
    private String devRecipient;

    public FileEventConsumer(EmailNotificationService emailService,
                             ProcessedEventRepository processedEventRepository,
                             UserServiceClient userServiceClient) {
        this.emailService = emailService;
        this.processedEventRepository = processedEventRepository;
        this.userServiceClient = userServiceClient;
    }

    @KafkaListener(
        topics = {"file.uploaded", "file.updated", "file.deleted", "file.conflict"},
        groupId = "${spring.kafka.consumer.group-id}"
    )
    public void handleFileEvent(FileEvent event) {
        log.debug("Received event [{}] type=[{}] userId=[{}]",
                  event.getEventId(), event.getEventType(), event.getUserId());

        if (event.getEventId() == null) {
            log.warn("Event with null eventId received — skipping");
            return;
        }

        // Idempotency: skip already-processed events
        if (processedEventRepository.existsById(event.getEventId())) {
            log.info("Duplicate event [{}] — skipping", event.getEventId());
            return;
        }

        String recipient = resolveRecipient(event.getUserId());

        switch (event.getEventType()) {
            case "file.uploaded" ->
                emailService.sendFileUploadedEmail(recipient, event.getFileName());
            case "file.updated" ->
                emailService.sendFileUpdatedEmail(recipient, event.getFileName(), event.getVersionNumber());
            case "file.deleted" ->
                emailService.sendFileDeletedEmail(recipient, event.getFileName());
            case "file.conflict" ->
                emailService.sendFileConflictEmail(recipient, event.getFileName());
            default ->
                log.warn("Unknown event type [{}] for event [{}] — skipping",
                         event.getEventType(), event.getEventId());
        }

        processedEventRepository.save(new ProcessedEvent(event.getEventId(), Instant.now()));
        log.info("Processed event [{}] type=[{}]", event.getEventId(), event.getEventType());
    }

    String resolveRecipient(String userId) {
        if (devRecipient != null && !devRecipient.isBlank()) {
            return devRecipient;
        }
        return userServiceClient.getEmailByUserId(userId);
    }

    // TODO Phase 2.5: Add @KafkaListener(topics = "payment.confirmed")
    // public void handlePaymentConfirmed(PaymentEvent event) {
    //     emailService.sendPaymentReceipt(event);
    // }
}
