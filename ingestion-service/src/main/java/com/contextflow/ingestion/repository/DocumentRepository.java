package com.contextflow.ingestion.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.contextflow.ingestion.entity.Document;
import com.contextflow.ingestion.entity.DocumentStatus;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Optional<Document> findByJobIdAndTenantId(UUID jobId, UUID tenantId);

    Page<Document> findAllByTenantId(UUID tenantId, Pageable pageable);

    Page<Document> findAllByTenantIdAndStatus(UUID tenantId, DocumentStatus status, Pageable pageable);
}
