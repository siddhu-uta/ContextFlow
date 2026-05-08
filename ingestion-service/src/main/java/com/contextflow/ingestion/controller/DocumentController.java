package com.contextflow.ingestion.controller;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.contextflow.ingestion.dto.DocumentListResponse;
import com.contextflow.ingestion.dto.DocumentStatusResponse;
import com.contextflow.ingestion.dto.UploadResponse;
import com.contextflow.ingestion.entity.DocumentStatus;
import com.contextflow.ingestion.security.TenantAwarePrincipal;
import com.contextflow.ingestion.service.DocumentService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> upload(@RequestPart("file") MultipartFile file,
                                                  Authentication authentication) {
        TenantAwarePrincipal principal = (TenantAwarePrincipal) authentication.getPrincipal();
        UploadResponse response = documentService.upload(file, principal.tenantId(), principal.userId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/jobs/{jobId}/status")
    public ResponseEntity<DocumentStatusResponse> getStatus(@PathVariable UUID jobId,
                                                              Authentication authentication) {
        TenantAwarePrincipal principal = (TenantAwarePrincipal) authentication.getPrincipal();
        return ResponseEntity.ok(documentService.getStatus(jobId, principal.tenantId()));
    }

    @GetMapping
    public ResponseEntity<Page<DocumentListResponse>> listDocuments(
            @RequestParam(required = false) DocumentStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        TenantAwarePrincipal principal = (TenantAwarePrincipal) authentication.getPrincipal();
        return ResponseEntity.ok(documentService.listDocuments(principal.tenantId(), status, page, size));
    }
}
