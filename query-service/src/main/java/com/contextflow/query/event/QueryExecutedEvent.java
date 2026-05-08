package com.contextflow.query.event;

import java.time.Instant;

public record QueryExecutedEvent(
        String tenantId,
        String question,
        int chunksRetrieved,
        boolean cacheHit,
        long latencyMs,
        Instant executedAt
) {}
