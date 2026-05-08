package com.contextflow.ingestion.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.contextflow.ingestion.event.DocumentUploadedEvent;
import com.contextflow.ingestion.service.S3StorageService;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@SuppressWarnings("resource") // Testcontainers lifecycle managed by JUnit Jupiter extension
class DocumentControllerIntegrationTest {

    private static final String JWT_SECRET =
            "contextflow-dev-secret-key-must-be-at-least-32-chars-long!!";

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("contextflow")
            .withUsername("contextflow")
            .withPassword("contextflow")
            .withInitScript("init-test-db.sql");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> postgres.getJdbcUrl() + "?currentSchema=ingestion");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Use a no-op Kafka — we mock KafkaTemplate below
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
        registry.add("spring.kafka.consumer.auto-offset-reset", () -> "latest");
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration");
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private S3StorageService s3StorageService;

    @MockBean
    private KafkaTemplate<String, DocumentUploadedEvent> kafkaTemplate;

    // ── Upload ────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("null") // Mockito any() matchers trigger IDE null-analysis false positives
    void upload_validPdf_returns202WithJobId() throws Exception {
        when(s3StorageService.upload(any(), any(), any())).thenReturn("tenant/doc/raw.pdf");
        when(kafkaTemplate.send(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf", "%PDF-1.4 fake content".getBytes());

        mockMvc.perform(multipart("/api/v1/documents/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").isNotEmpty())
                .andExpect(jsonPath("$.documentId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void upload_unsupportedFileType_returns415() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "image.png", "image/png", "fake-png-data".getBytes());

        mockMvc.perform(multipart("/api/v1/documents/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void upload_noAuthToken_returns401() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf", "content".getBytes());

        mockMvc.perform(multipart("/api/v1/documents/upload").file(file))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @SuppressWarnings("null")
    void upload_plainText_returns202() throws Exception {
        when(s3StorageService.upload(any(), any(), any())).thenReturn("tenant/doc/raw.txt");
        when(kafkaTemplate.send(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "Hello world notes".getBytes());

        mockMvc.perform(multipart("/api/v1/documents/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isAccepted());
    }

    // ── Status ────────────────────────────────────────────────────────────────

    @Test
    void getStatus_unknownJobId_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/documents/jobs/{jobId}/status", UUID.randomUUID())
                        .header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getStatus_noAuthToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/documents/jobs/{jobId}/status", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    // ── List ──────────────────────────────────────────────────────────────────

    @Test
    void listDocuments_noAuthToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/documents"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listDocuments_validToken_returns200WithPage() throws Exception {
        mockMvc.perform(get("/api/v1/documents")
                        .header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private String validToken() {
        SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(UUID.randomUUID().toString())
                .claim("tenantId", UUID.randomUUID().toString())
                .claim("role", "ADMIN")
                .expiration(Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }
}
