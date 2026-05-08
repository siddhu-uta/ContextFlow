package com.contextflow.query.controller;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.contextflow.query.dto.QueryRequest;
import com.contextflow.query.security.TenantAwarePrincipal;
import com.contextflow.query.service.QueryService;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/v1")
@Slf4j
public class QueryController {

    private final QueryService queryService;
    private final Executor queryExecutor;

    public QueryController(QueryService queryService,
                           @Qualifier("queryExecutor") Executor queryExecutor) {
        this.queryService = queryService;
        this.queryExecutor = queryExecutor;
    }

    /**
     * Streams an LLM answer grounded in the tenant's documents.
     *
     * SSE event sequence:
     *   1. event: sources  — JSON array of retrieved chunk citations
     *   2. event: token    — repeated, one per LLM output token
     *   3. event: done     — signals end of stream (or event: error on failure)
     */
    @PostMapping(value = "/query", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter query(@Valid @RequestBody QueryRequest request,
                             Authentication authentication) {
        TenantAwarePrincipal principal = (TenantAwarePrincipal) authentication.getPrincipal();

        SseEmitter emitter = new SseEmitter(120_000L); // 2-minute max for slow LLMs

        CompletableFuture.runAsync(
                () -> queryService.executeQuery(request.question(), principal.tenantId(), emitter),
                queryExecutor
        );

        log.info("Query accepted for tenant={} question='{}'",
                principal.tenantId(), request.question().substring(0, Math.min(60, request.question().length())));

        return emitter;
    }
}
