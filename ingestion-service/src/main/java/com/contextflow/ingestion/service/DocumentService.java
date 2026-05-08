package com.contextflow.ingestion.service;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.contextflow.ingestion.config.IngestionProperties;
import com.contextflow.ingestion.dto.DocumentListResponse;
import com.contextflow.ingestion.dto.DocumentStatusResponse;
import com.contextflow.ingestion.dto.UploadResponse;
import com.contextflow.ingestion.entity.Document;
import com.contextflow.ingestion.entity.DocumentStatus;
import com.contextflow.ingestion.event.DocumentUploadedEvent;
import com.contextflow.ingestion.exception.DocumentNotFoundException;
import com.contextflow.ingestion.exception.UnsupportedFileTypeException;
import com.contextflow.ingestion.repository.DocumentRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/plain"
    );

    private final DocumentRepository documentRepository;
    private final S3StorageService s3StorageService;
    private final KafkaTemplate<String, DocumentUploadedEvent> kafkaTemplate;
    private final IngestionProperties properties;

    @Transactional
    public UploadResponse upload(MultipartFile file, UUID tenantId, UUID uploadedBy) {
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new UnsupportedFileTypeException(
                "Unsupported file type: %s. Allowed: PDF, DOCX, TXT".formatted(contentType));
        }

        UUID documentId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();

        String s3Key = s3StorageService.upload(file, tenantId, documentId);

        Document document = new Document();
        document.setId(documentId);
        document.setTenantId(tenantId);
        document.setUploadedBy(uploadedBy);
        document.setOriginalFilename(file.getOriginalFilename());
        document.setS3Key(s3Key);
        document.setContentType(contentType);
        document.setFileSizeBytes(file.getSize());
        document.setJobId(jobId);
        documentRepository.save(document);

        DocumentUploadedEvent event = new DocumentUploadedEvent(
                documentId.toString(),
                jobId.toString(),
                tenantId.toString(),
                s3Key,
                file.getOriginalFilename(),
                contentType,
                file.getSize(),
                Instant.now()
        );

        kafkaTemplate.send(properties.kafka().documentUploaded(), tenantId.toString(), event);
        log.info("Published document.uploaded event for documentId={} tenantId={}", documentId, tenantId);

        return new UploadResponse(documentId, jobId, file.getOriginalFilename(),
                file.getSize(), DocumentStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public DocumentStatusResponse getStatus(UUID jobId, UUID tenantId) {
        Document doc = documentRepository.findByJobIdAndTenantId(jobId, tenantId)
                .orElseThrow(() -> new DocumentNotFoundException("Job not found: " + jobId));

        return DocumentStatusResponse.from(doc);
    }

    @Transactional(readOnly = true)
    public Page<DocumentListResponse> listDocuments(UUID tenantId, DocumentStatus status,
                                                     int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        if (status != null) {
            return documentRepository.findAllByTenantIdAndStatus(tenantId, status, pageable)
                    .map(DocumentListResponse::from);
        }

        return documentRepository.findAllByTenantId(tenantId, pageable)
                .map(DocumentListResponse::from);
    }
}
