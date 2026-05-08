package com.contextflow.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.contextflow.auth.dto.LoginRequest;
import com.contextflow.auth.dto.RefreshTokenRequest;
import com.contextflow.auth.dto.RegisterTenantRequest;
import com.contextflow.auth.dto.TokenResponse;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@SuppressWarnings("resource") // Testcontainers lifecycle managed by JUnit Jupiter extension
class AuthControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("contextflow")
            .withUsername("contextflow")
            .withPassword("contextflow")
            .withInitScript("init-test-db.sql");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> postgres.getJdbcUrl() + "?currentSchema=auth");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    // ── Register ─────────────────────────────────────────────────────────────

    @Test
    void register_validRequest_returns201WithTokens() {
        RegisterTenantRequest req = uniqueRegisterRequest();

        ResponseEntity<TokenResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/register", req, TokenResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TokenResponse body = java.util.Objects.requireNonNull(response.getBody());
        assertThat(body.accessToken()).isNotBlank();
        assertThat(body.refreshToken()).isNotBlank();
        assertThat(body.tokenType()).isEqualTo("Bearer");
        assertThat(body.userId()).isNotNull();
        assertThat(body.tenantId()).isNotNull();
        assertThat(body.role()).isEqualTo("ADMIN");
    }

    @Test
    void register_duplicateEmail_returns409() {
        RegisterTenantRequest first = uniqueRegisterRequest();
        restTemplate.postForEntity("/api/v1/auth/register", first, TokenResponse.class);

        // Same email, different org
        RegisterTenantRequest dup = new RegisterTenantRequest(
                "Other Corp", "other-" + uniqueSlug(), first.adminEmail(), "password123");

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/register", dup, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void register_duplicateSlug_returns409() {
        RegisterTenantRequest first = uniqueRegisterRequest();
        restTemplate.postForEntity("/api/v1/auth/register", first, TokenResponse.class);

        // Same slug, different email
        RegisterTenantRequest dup = new RegisterTenantRequest(
                "Other Corp", first.slug(), "other-" + UUID.randomUUID() + "@test.com", "password123");

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/register", dup, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void register_invalidEmail_returns400() {
        RegisterTenantRequest req = new RegisterTenantRequest(
                "Corp", uniqueSlug(), "not-an-email", "password123");

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/register", req, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    @Test
    void login_validCredentials_returns200WithTokens() {
        RegisterTenantRequest req = uniqueRegisterRequest();
        restTemplate.postForEntity("/api/v1/auth/register", req, TokenResponse.class);

        LoginRequest loginReq = new LoginRequest(req.adminEmail(), req.adminPassword());
        ResponseEntity<TokenResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/login", loginReq, TokenResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(java.util.Objects.requireNonNull(response.getBody()).accessToken()).isNotBlank();
    }

    @Test
    void login_wrongPassword_returns401() {
        RegisterTenantRequest req = uniqueRegisterRequest();
        restTemplate.postForEntity("/api/v1/auth/register", req, TokenResponse.class);

        LoginRequest loginReq = new LoginRequest(req.adminEmail(), "wrongpassword");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/login", loginReq, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void login_unknownEmail_returns401() {
        LoginRequest loginReq = new LoginRequest("nobody@test.com", "password123");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/login", loginReq, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    @Test
    void refresh_validToken_returns200WithNewAccessToken() {
        TokenResponse tokens = register();

        RefreshTokenRequest req = new RefreshTokenRequest(tokens.refreshToken());
        ResponseEntity<TokenResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/refresh", req, TokenResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // New access token must differ from the original
        assertThat(java.util.Objects.requireNonNull(response.getBody()).accessToken())
                .isNotEqualTo(tokens.accessToken());
    }

    @Test
    void refresh_invalidToken_returns401() {
        RefreshTokenRequest req = new RefreshTokenRequest("not-a-valid-token");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/refresh", req, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refresh_tokenReuse_returns401() {
        TokenResponse tokens = register();
        RefreshTokenRequest req = new RefreshTokenRequest(tokens.refreshToken());

        // First refresh succeeds, rotating the token
        restTemplate.postForEntity("/api/v1/auth/refresh", req, TokenResponse.class);

        // Second use of the same refresh token is rejected (rotation invalidates old token)
        ResponseEntity<String> second = restTemplate.postForEntity(
                "/api/v1/auth/refresh", req, String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    @Test
    void logout_validToken_returns204() {
        TokenResponse tokens = register();

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + tokens.accessToken());

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/auth/logout", HttpMethod.POST,
                new HttpEntity<>(headers), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private TokenResponse register() {
        return restTemplate.postForEntity(
                "/api/v1/auth/register", uniqueRegisterRequest(), TokenResponse.class
        ).getBody();
    }

    private RegisterTenantRequest uniqueRegisterRequest() {
        String id = UUID.randomUUID().toString().substring(0, 8);
        return new RegisterTenantRequest(
                "Test Corp " + id,
                "corp-" + id,
                "admin-" + id + "@test.com",
                "password123"
        );
    }

    private String uniqueSlug() {
        return "slug-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
