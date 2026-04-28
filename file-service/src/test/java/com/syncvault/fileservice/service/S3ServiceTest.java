package com.syncvault.fileservice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.MalformedURLException;
import java.net.URL;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class S3ServiceTest {

    @Mock S3Client s3Client;
    @Mock S3Presigner s3Presigner;

    S3Service s3Service;

    @BeforeEach
    void setUp() {
        s3Service = new S3Service(s3Client, s3Presigner);
        ReflectionTestUtils.setField(s3Service, "bucket", "test-bucket");
        ReflectionTestUtils.setField(s3Service, "presignedUrlDurationMinutes", 15);
    }

    @Test
    void uploadFile_callsPutObject() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        s3Service.uploadFile("user/file/1/test.txt", "hello".getBytes(), "text/plain");

        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void uploadFile_nullContentType_usesDefault() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        s3Service.uploadFile("key", "data".getBytes(), null);

        verify(s3Client).putObject(argThat((PutObjectRequest r) ->
                "application/octet-stream".equals(r.contentType())), any(RequestBody.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void downloadFile_returnsBytes() {
        byte[] expected = "file content".getBytes();
        ResponseBytes<GetObjectResponse> responseBytes = mock(ResponseBytes.class);
        when(responseBytes.asByteArray()).thenReturn(expected);
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(responseBytes);

        byte[] result = s3Service.downloadFile("user/file/1/doc.txt");

        assertThat(result).isEqualTo(expected);
        verify(s3Client).getObjectAsBytes(argThat((GetObjectRequest r) ->
                "test-bucket".equals(r.bucket()) && "user/file/1/doc.txt".equals(r.key())));
    }

    @Test
    void generatePresignedUrl_returnsUrl() throws MalformedURLException {
        PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(new URL("https://s3.example.com/bucket/key?sig=abc"));
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

        String url = s3Service.generatePresignedUrl("user/file/1/test.txt");

        assertThat(url).isEqualTo("https://s3.example.com/bucket/key?sig=abc");
    }
}
