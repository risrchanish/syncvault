package com.syncvault.notificationservice.consumer;

import com.syncvault.notificationservice.client.UserServiceClient;
import com.syncvault.notificationservice.dto.FileEvent;
import com.syncvault.notificationservice.entity.ProcessedEvent;
import com.syncvault.notificationservice.repository.ProcessedEventRepository;
import com.syncvault.notificationservice.service.EmailNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileEventConsumerTest {

    @Mock
    private EmailNotificationService emailService;
    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private UserServiceClient userServiceClient;

    private FileEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new FileEventConsumer(emailService, processedEventRepository, userServiceClient);
        ReflectionTestUtils.setField(consumer, "devRecipient", "dev@example.com");
    }

    @Test
    void handleFileEvent_uploaded_delegatesToEmailService() {
        FileEvent event = buildEvent("file.uploaded", "report.pdf");
        when(processedEventRepository.existsById(event.getEventId())).thenReturn(false);

        consumer.handleFileEvent(event);

        verify(emailService).sendFileUploadedEmail("dev@example.com", "report.pdf");
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    void handleFileEvent_updated_delegatesToEmailService() {
        FileEvent event = buildEvent("file.updated", "doc.txt");
        event.setVersionNumber(2);
        when(processedEventRepository.existsById(event.getEventId())).thenReturn(false);

        consumer.handleFileEvent(event);

        verify(emailService).sendFileUpdatedEmail("dev@example.com", "doc.txt", 2);
    }

    @Test
    void handleFileEvent_deleted_delegatesToEmailService() {
        FileEvent event = buildEvent("file.deleted", "old.pdf");
        when(processedEventRepository.existsById(event.getEventId())).thenReturn(false);

        consumer.handleFileEvent(event);

        verify(emailService).sendFileDeletedEmail("dev@example.com", "old.pdf");
    }

    @Test
    void handleFileEvent_conflict_delegatesToEmailService() {
        FileEvent event = buildEvent("file.conflict", "budget.xlsx");
        when(processedEventRepository.existsById(event.getEventId())).thenReturn(false);

        consumer.handleFileEvent(event);

        verify(emailService).sendFileConflictEmail("dev@example.com", "budget.xlsx");
    }

    @Test
    void handleFileEvent_duplicate_skipsEmail() {
        FileEvent event = buildEvent("file.uploaded", "report.pdf");
        when(processedEventRepository.existsById(event.getEventId())).thenReturn(true);

        consumer.handleFileEvent(event);

        verifyNoInteractions(emailService);
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void handleFileEvent_nullEventId_skipsProcessing() {
        FileEvent event = buildEvent("file.uploaded", "report.pdf");
        event.setEventId(null);

        consumer.handleFileEvent(event);

        verifyNoInteractions(emailService);
        verifyNoInteractions(processedEventRepository);
    }

    @Test
    void handleFileEvent_unknownType_skipsEmail() {
        FileEvent event = buildEvent("file.mystery", "test.txt");
        when(processedEventRepository.existsById(event.getEventId())).thenReturn(false);

        consumer.handleFileEvent(event);

        verifyNoInteractions(emailService);
        // Event is still marked processed to avoid repeated noise
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    void resolveRecipient_devRecipientSet_returnsDevRecipient() {
        assertThat(consumer.resolveRecipient("any-user-id")).isEqualTo("dev@example.com");
        verifyNoInteractions(userServiceClient);
    }

    @Test
    void resolveRecipient_devRecipientEmpty_callsUserServiceClient() {
        ReflectionTestUtils.setField(consumer, "devRecipient", "");
        when(userServiceClient.getEmailByUserId(anyString())).thenReturn("real@user.com");

        String result = consumer.resolveRecipient("user-123");

        assertThat(result).isEqualTo("real@user.com");
        verify(userServiceClient).getEmailByUserId("user-123");
    }

    private FileEvent buildEvent(String eventType, String fileName) {
        return new FileEvent(
                UUID.randomUUID().toString(), eventType,
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                fileName, 1024L, "2025-01-01T10:00:00Z", null
        );
    }
}
