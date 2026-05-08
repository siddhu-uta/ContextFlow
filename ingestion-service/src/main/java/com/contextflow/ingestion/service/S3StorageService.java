package com.contextflow.ingestion.service;

import java.io.IOException;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.contextflow.ingestion.config.IngestionProperties;
import com.contextflow.ingestion.exception.StorageException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
@RequiredArgsConstructor
@Slf4j
public class S3StorageService {

    private final S3Client s3Client;
    private final IngestionProperties properties;

    public String upload(MultipartFile file, UUID tenantId, UUID documentId) {
        String extension = extractExtension(file.getOriginalFilename());
        String s3Key = "%s/%s/raw.%s".formatted(tenantId, documentId, extension);

        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(properties.s3().bucket())
                    .key(s3Key)
                    .contentType(file.getContentType())
                    .contentLength(file.getSize())
                    .build();

            s3Client.putObject(request, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
            log.info("Uploaded document to S3: {}", s3Key);
            return s3Key;

        } catch (IOException e) {
            throw new StorageException("Failed to read uploaded file: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new StorageException("Failed to upload to S3: " + e.getMessage(), e);
        }
    }

    public void delete(String s3Key) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(properties.s3().bucket())
                .key(s3Key)
                .build());
    }

    private String extractExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "bin";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }
}
