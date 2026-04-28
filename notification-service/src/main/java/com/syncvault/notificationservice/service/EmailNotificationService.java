package com.syncvault.notificationservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class EmailNotificationService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:noreply@syncvault.com}")
    private String fromAddress;

    public EmailNotificationService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendFileUploadedEmail(String to, String fileName) {
        send(to,
             "SyncVault — File Uploaded",
             "Your file \"" + fileName + "\" was uploaded successfully.");
    }

    public void sendFileUpdatedEmail(String to, String fileName, Integer versionNumber) {
        // versionNumber is null until file-service publishes it in the Kafka event
        String body = (versionNumber != null)
                ? "Your file \"" + fileName + "\" was updated (version " + versionNumber + ")."
                : "Your file \"" + fileName + "\" was updated successfully.";
        send(to, "SyncVault — File Updated", body);
    }

    public void sendFileDeletedEmail(String to, String fileName) {
        send(to,
             "SyncVault — File Moved to Trash",
             "Your file \"" + fileName + "\" has been moved to trash. You can restore it within 30 days.");
    }

    public void sendFileConflictEmail(String to, String fileName) {
        send(to,
             "SyncVault — Conflict Detected",
             "A conflict was detected on \"" + fileName + "\" — a conflict copy has been created.");
    }

    private void send(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
        log.info("Email sent to [{}] — subject: {}", to, subject);
    }
}
