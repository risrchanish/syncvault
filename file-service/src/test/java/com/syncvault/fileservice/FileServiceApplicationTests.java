package com.syncvault.fileservice;

import com.syncvault.fileservice.client.UserStorageClient;
import com.syncvault.fileservice.kafka.FileEventProducer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.kafka.bootstrap-servers=localhost:9092",
    "spring.kafka.admin.fail-fast=false",
    "aws.s3.endpoint-override=http://localhost:4566"
})
class FileServiceApplicationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg15");

    @MockBean S3Client s3Client;
    @MockBean S3Presigner s3Presigner;
    @MockBean FileEventProducer fileEventProducer;
    @MockBean UserStorageClient userStorageClient;

    @Test
    void contextLoads() {
    }
}
