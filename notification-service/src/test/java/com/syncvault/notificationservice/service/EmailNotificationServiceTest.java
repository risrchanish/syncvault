package com.syncvault.notificationservice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EmailNotificationServiceTest {

    @Mock
    private JavaMailSender mailSender;

    private EmailNotificationService emailService;

    @BeforeEach
    void setUp() {
        emailService = new EmailNotificationService(mailSender);
        ReflectionTestUtils.setField(emailService, "fromAddress", "noreply@syncvault.com");
    }

    @Test
    void sendFileUploadedEmail_correctSubjectAndBody() {
        emailService.sendFileUploadedEmail("user@example.com", "report.pdf");

        SimpleMailMessage msg = captureMessage();
        assertThat(msg.getTo()).containsExactly("user@example.com");
        assertThat(msg.getFrom()).isEqualTo("noreply@syncvault.com");
        assertThat(msg.getSubject()).contains("Uploaded");
        assertThat(msg.getText()).contains("report.pdf").contains("uploaded successfully");
    }

    @Test
    void sendFileUpdatedEmail_withVersionNumber_includesVersion() {
        emailService.sendFileUpdatedEmail("user@example.com", "notes.txt", 3);

        SimpleMailMessage msg = captureMessage();
        assertThat(msg.getSubject()).contains("Updated");
        assertThat(msg.getText()).contains("notes.txt").contains("version 3");
    }

    @Test
    void sendFileUpdatedEmail_nullVersion_fallsBackToGenericMessage() {
        emailService.sendFileUpdatedEmail("user@example.com", "notes.txt", null);

        SimpleMailMessage msg = captureMessage();
        assertThat(msg.getText()).contains("notes.txt").contains("updated successfully");
        assertThat(msg.getText()).doesNotContain("version");
    }

    @Test
    void sendFileDeletedEmail_correctSubjectAndBody() {
        emailService.sendFileDeletedEmail("user@example.com", "old-doc.docx");

        SimpleMailMessage msg = captureMessage();
        assertThat(msg.getSubject()).contains("Trash");
        assertThat(msg.getText()).contains("old-doc.docx").contains("trash").contains("30 days");
    }

    @Test
    void sendFileConflictEmail_correctSubjectAndBody() {
        emailService.sendFileConflictEmail("user@example.com", "budget.xlsx");

        SimpleMailMessage msg = captureMessage();
        assertThat(msg.getSubject()).contains("Conflict");
        assertThat(msg.getText()).contains("budget.xlsx").contains("conflict copy");
    }

    private SimpleMailMessage captureMessage() {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }
}
