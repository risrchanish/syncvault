package com.syncvault.fileservice.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class S3Service {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    @Value("${aws.s3.bucket}")
    private String bucket;

    @Value("${aws.s3.presigned-url-duration-minutes:15}")
    private int presignedUrlDurationMinutes;

    public void uploadFile(String s3Key, byte[] data, String contentType) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(s3Key)
                .contentType(contentType != null ? contentType : "application/octet-stream")
                .contentLength((long) data.length)
                .build();

        s3Client.putObject(request, RequestBody.fromBytes(data));
    }

    public byte[] downloadFile(String s3Key) {
        return s3Client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(s3Key).build()
        ).asByteArray();
    }

    public String generatePresignedUrl(String s3Key) {
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(presignedUrlDurationMinutes))
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(s3Key)
                        .build())
                .build();

        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }
}
