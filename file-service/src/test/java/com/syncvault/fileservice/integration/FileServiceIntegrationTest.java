package com.syncvault.fileservice.integration;

import com.syncvault.fileservice.client.UserStorageClient;
import com.syncvault.fileservice.dto.response.*;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1,
        topics = {"file.uploaded", "file.updated", "file.deleted", "file.conflict"})
class FileServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg15");

    @Container
    static LocalStackContainer localstack =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0"))
                    .withServices(LocalStackContainer.Service.S3);

    @DynamicPropertySource
    static void configureLocalStack(DynamicPropertyRegistry registry) {
        registry.add("aws.s3.endpoint-override",
                () -> localstack.getEndpointOverride(LocalStackContainer.Service.S3).toString());
    }

    @MockBean
    UserStorageClient userStorageClient;

    @Autowired
    TestRestTemplate restTemplate;

    @Value("${jwt.secret}")
    String jwtSecret;

    @BeforeAll
    static void createBucket() {
        S3Client s3 = S3Client.builder()
                .region(Region.US_EAST_1)
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.S3))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")))
                .forcePathStyle(true)
                .build();
        s3.createBucket(CreateBucketRequest.builder().bucket("syncvault-files-test").build());
        s3.close();
    }

    // ── Upload + Get ─────────────────────────────────────────────────────────

    @Test
    void uploadFile_thenGetFile_returnsPresignedUrl() {
        UUID userId = UUID.randomUUID();
        String token = generateToken(userId.toString());

        ResponseEntity<UploadResponse> uploadResp = restTemplate.exchange(
                "/files/upload", HttpMethod.POST,
                multipartRequest(token, "hello world".getBytes(), "report.txt", "text/plain", null),
                UploadResponse.class);

        assertThat(uploadResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String fileId = uploadResp.getBody().getFileId();
        assertThat(fileId).isNotBlank();
        assertThat(uploadResp.getBody().getVersionNumber()).isEqualTo(1);

        ResponseEntity<FileInfoResponse> getResp = restTemplate.exchange(
                "/files/" + fileId, HttpMethod.GET,
                bearer(token), FileInfoResponse.class);

        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResp.getBody().getDownloadUrl()).isNotBlank();
    }

    // ── List user files ──────────────────────────────────────────────────────

    @Test
    void listUserFiles_returnsOwnFiles() {
        UUID userId = UUID.randomUUID();
        String token = generateToken(userId.toString());

        restTemplate.exchange("/files/upload", HttpMethod.POST,
                multipartRequest(token, "a".getBytes(), "a.txt", "text/plain", null),
                UploadResponse.class);

        ResponseEntity<List<FileListItemResponse>> listResp = restTemplate.exchange(
                "/files/user/" + userId, HttpMethod.GET,
                bearer(token),
                new ParameterizedTypeReference<>() {});

        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResp.getBody()).hasSize(1);
        assertThat(listResp.getBody().get(0).getFileName()).isEqualTo("a.txt");
    }

    @Test
    void listUserFiles_otherUser_returns403() {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        ResponseEntity<String> resp = restTemplate.exchange(
                "/files/user/" + owner, HttpMethod.GET,
                bearer(generateToken(other.toString())),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── Update (safe) ────────────────────────────────────────────────────────

    @Test
    void updateFile_safeUpdate_incrementsVersion() {
        UUID userId = UUID.randomUUID();
        String token = generateToken(userId.toString());

        UploadResponse upload = restTemplate.exchange("/files/upload", HttpMethod.POST,
                multipartRequest(token, "v1".getBytes(), "doc.txt", "text/plain", null),
                UploadResponse.class).getBody();

        ResponseEntity<UpdateResponse> updateResp = restTemplate.exchange(
                "/files/" + upload.getFileId(), HttpMethod.PUT,
                multipartRequest(token, "v2".getBytes(), "doc.txt", "text/plain", "1"),
                UpdateResponse.class);

        assertThat(updateResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updateResp.getBody().getNewVersionNumber()).isEqualTo(2);
    }

    // ── Update (conflict) ────────────────────────────────────────────────────

    @Test
    void updateFile_staleBaseVersion_returns409WithConflictCopy() {
        UUID userId = UUID.randomUUID();
        String token = generateToken(userId.toString());

        UploadResponse upload = restTemplate.exchange("/files/upload", HttpMethod.POST,
                multipartRequest(token, "original".getBytes(), "data.txt", "text/plain", null),
                UploadResponse.class).getBody();

        // Simulate stale base version (client thinks it's at v0)
        ResponseEntity<ConflictResponse> conflictResp = restTemplate.exchange(
                "/files/" + upload.getFileId(), HttpMethod.PUT,
                multipartRequest(token, "conflict".getBytes(), "data.txt", "text/plain", "0"),
                ConflictResponse.class);

        assertThat(conflictResp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflictResp.getBody().getMessage()).isEqualTo("Conflict detected");
        assertThat(conflictResp.getBody().getConflictCopyId()).isNotBlank();
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    @Test
    void deleteFile_softDeletes_notFoundAfterDelete() {
        UUID userId = UUID.randomUUID();
        String token = generateToken(userId.toString());

        UploadResponse upload = restTemplate.exchange("/files/upload", HttpMethod.POST,
                multipartRequest(token, "bye".getBytes(), "gone.txt", "text/plain", null),
                UploadResponse.class).getBody();

        ResponseEntity<MessageResponse> del = restTemplate.exchange(
                "/files/" + upload.getFileId(), HttpMethod.DELETE,
                bearer(token), MessageResponse.class);

        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(del.getBody().getMessage()).isEqualTo("File moved to trash");

        ResponseEntity<String> getAfterDelete = restTemplate.exchange(
                "/files/" + upload.getFileId(), HttpMethod.GET,
                bearer(token), String.class);
        assertThat(getAfterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── Versions ─────────────────────────────────────────────────────────────

    @Test
    void getVersions_returnsAllVersions() {
        UUID userId = UUID.randomUUID();
        String token = generateToken(userId.toString());

        UploadResponse upload = restTemplate.exchange("/files/upload", HttpMethod.POST,
                multipartRequest(token, "v1".getBytes(), "file.txt", "text/plain", null),
                UploadResponse.class).getBody();

        restTemplate.exchange("/files/" + upload.getFileId(), HttpMethod.PUT,
                multipartRequest(token, "v2".getBytes(), "file.txt", "text/plain", "1"),
                UpdateResponse.class);

        ResponseEntity<List<VersionListItemResponse>> versions = restTemplate.exchange(
                "/files/" + upload.getFileId() + "/versions", HttpMethod.GET,
                bearer(token), new ParameterizedTypeReference<>() {});

        assertThat(versions.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(versions.getBody()).hasSize(2);
    }

    // ── Restore ──────────────────────────────────────────────────────────────

    @Test
    void restoreVersion_createsNewVersionWithOldContent() {
        UUID userId = UUID.randomUUID();
        String token = generateToken(userId.toString());

        UploadResponse upload = restTemplate.exchange("/files/upload", HttpMethod.POST,
                multipartRequest(token, "v1".getBytes(), "file.txt", "text/plain", null),
                UploadResponse.class).getBody();

        restTemplate.exchange("/files/" + upload.getFileId(), HttpMethod.PUT,
                multipartRequest(token, "v2".getBytes(), "file.txt", "text/plain", "1"),
                UpdateResponse.class);

        ResponseEntity<RestoreResponse> restore = restTemplate.exchange(
                "/files/" + upload.getFileId() + "/versions/1/restore", HttpMethod.GET,
                bearer(token), RestoreResponse.class);

        assertThat(restore.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(restore.getBody().getRestoredVersion()).isEqualTo(3);
    }

    // ── Security ─────────────────────────────────────────────────────────────

    @Test
    void noToken_returns401() {
        ResponseEntity<String> resp = restTemplate.getForEntity(
                "/files/" + UUID.randomUUID(), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void getFile_otherUsersFile_returns403() {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        String ownerToken = generateToken(owner.toString());

        UploadResponse upload = restTemplate.exchange("/files/upload", HttpMethod.POST,
                multipartRequest(ownerToken, "secret".getBytes(), "priv.txt", "text/plain", null),
                UploadResponse.class).getBody();

        ResponseEntity<String> resp = restTemplate.exchange(
                "/files/" + upload.getFileId(), HttpMethod.GET,
                bearer(generateToken(other.toString())), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String generateToken(String userId) {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        Date now = new Date();
        return Jwts.builder()
                .subject(userId)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 900_000L))
                .signWith(key)
                .compact();
    }

    private HttpEntity<MultiValueMap<String, Object>> multipartRequest(
            String token, byte[] content, String filename, String contentType, String baseVersion) {

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType(contentType));
        fileHeaders.setContentDispositionFormData("file", filename);
        body.add("file", new HttpEntity<>(content, fileHeaders));

        if (baseVersion != null) {
            body.add("baseVersion", baseVersion);
        }

        return new HttpEntity<>(body, headers);
    }

    private HttpEntity<Void> bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return new HttpEntity<>(h);
    }
}
