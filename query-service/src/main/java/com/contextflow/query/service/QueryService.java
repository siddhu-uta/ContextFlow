package com.contextflow.query.service;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.contextflow.query.config.QueryProperties;
import com.contextflow.query.dto.ChunkSearchResult;
import com.contextflow.query.event.QueryExecutedEvent;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class QueryService {

    private static final String TOPIC_QUERY_EXECUTED = "query.executed";
    private static final String NO_CONTEXT_ANSWER =
            "I couldn't find relevant information in your documents to answer this question.";

    private final EmbeddingClient embeddingClient;
    private final VectorSearchService vectorSearchService;
    private final LlmStreamingService llmService;
    private final SemanticCacheService cacheService;
    private final KafkaTemplate<String, QueryExecutedEvent> kafkaTemplate;
    private final QueryProperties properties;
    private final MeterRegistry meterRegistry;

    public QueryService(EmbeddingClient embeddingClient, VectorSearchService vectorSearchService,
                        LlmStreamingService llmService, SemanticCacheService cacheService,
                        KafkaTemplate<String, QueryExecutedEvent> kafkaTemplate,
                        QueryProperties properties, MeterRegistry meterRegistry) {
        this.embeddingClient = embeddingClient;
        this.vectorSearchService = vectorSearchService;
        this.llmService = llmService;
        this.cacheService = cacheService;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    public void executeQuery(String question, UUID tenantId, SseEmitter emitter) {
        Instant start = Instant.now();
        String tenant = tenantId.toString();

        // Per-tenant query execution timer — visible in Grafana as query_execution_seconds
        Timer.Sample timerSample = Timer.start(meterRegistry);

        try {
            // 1. Exact-match semantic cache check
            Optional<String> cached = cacheService.get(tenant, question);
            if (cached.isPresent()) {
                Counter.builder("query.cache.hits").tag("tenant", tenant).register(meterRegistry).increment();
                timerSample.stop(Timer.builder("query.execution").tag("tenant", tenant).register(meterRegistry));
                streamCachedAnswer(cached.get(), emitter);
                publishAnalyticsEvent(tenantId, question, 0, true, start);
                return;
            }
            Counter.builder("query.cache.misses").tag("tenant", tenant).register(meterRegistry).increment();

            // 2. Embed the query using the same model as the embedding worker
            float[] queryVector = embeddingClient.embedQuery(question);

            // 3. Vector similarity search — top-k chunks from tenant's documents
            List<ChunkSearchResult> chunks = vectorSearchService.search(tenantId, queryVector, properties.topK());

            // 4. Send source citations to the client before streaming begins
            emitter.send(SseEmitter.event()
                    .name("sources")
                    .data(Map.of(
                            "chunks", chunks.stream().map(this::toSourceMap).toList(),
                            "cached", false
                    )));

            if (chunks.isEmpty()) {
                emitter.send(SseEmitter.event().name("token").data(Map.of("text", NO_CONTEXT_ANSWER)));
                emitter.send(SseEmitter.event().name("done").data(Map.of()));
                emitter.complete();
                timerSample.stop(Timer.builder("query.execution").tag("tenant", tenant).register(meterRegistry));
                publishAnalyticsEvent(tenantId, question, 0, false, start);
                return;
            }

            // 5. Assemble RAG prompt from retrieved chunks
            String systemPrompt = buildSystemPrompt(chunks);

            // 6. Stream LLM response token-by-token — onComplete handles caching + analytics
            StringBuilder fullAnswer = new StringBuilder();
            llmService.stream(systemPrompt, question, emitter, fullAnswer, () -> {
                timerSample.stop(Timer.builder("query.execution").tag("tenant", tenant).register(meterRegistry));
                Counter.builder("query.executions").tag("tenant", tenant).register(meterRegistry).increment();
                cacheService.put(tenant, question, fullAnswer.toString());
                publishAnalyticsEvent(tenantId, question, chunks.size(), false, start);
            });

        } catch (IOException e) {
            log.error("SSE write failed for tenant {}: {}", tenantId, e.getMessage());
            emitter.completeWithError(e);
        } catch (Exception e) {
            log.error("Query failed for tenant {}: {}", tenantId, e.getMessage(), e);
            try {
                emitter.send(SseEmitter.event().name("error").data(Map.of("message", e.getMessage())));
            } catch (IOException ignored) {}
            emitter.completeWithError(e);
        }
    }

    private void streamCachedAnswer(String answer, SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event()
                    .name("sources")
                    .data(Map.of("chunks", List.of(), "cached", true)));

            // Stream cached answer word-by-word to preserve the streaming UX
            for (String word : answer.split("(?<=\\s)|(?=\\s)")) {
                emitter.send(SseEmitter.event().name("token").data(Map.of("text", word)));
            }
            emitter.send(SseEmitter.event().name("done").data(Map.of()));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private String buildSystemPrompt(List<ChunkSearchResult> chunks) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are a helpful AI assistant. Answer the user's question based ONLY on the \
                context below. If the answer is not in the context, say: "I don't have enough \
                information in the provided documents to answer this question." \
                When citing information, mention the source document and page number.

                Context:
                """);

        for (int i = 0; i < chunks.size(); i++) {
            ChunkSearchResult c = chunks.get(i);
            sb.append("---\n");
            sb.append("[Document: ").append(c.filename())
              .append(", Page ").append(c.pageNumber())
              .append(", Relevance: ").append(String.format("%.0f%%", c.similarity() * 100))
              .append("]\n");
            sb.append(c.content()).append("\n\n");
        }
        sb.append("---");
        return sb.toString();
    }

    private Map<String, Object> toSourceMap(ChunkSearchResult c) {
        return Map.of(
                "filename", c.filename(),
                "page", c.pageNumber(),
                "snippet", c.content().length() > 200 ? c.content().substring(0, 200) + "…" : c.content(),
                "similarity", Math.round(c.similarity() * 100.0) / 100.0
        );
    }

    private void publishAnalyticsEvent(UUID tenantId, String question,
                                        int chunksRetrieved, boolean cacheHit, Instant start) {
        long latencyMs = Instant.now().toEpochMilli() - start.toEpochMilli();
        QueryExecutedEvent event = new QueryExecutedEvent(
                tenantId.toString(), question, chunksRetrieved, cacheHit, latencyMs, Instant.now()
        );
        kafkaTemplate.send(TOPIC_QUERY_EXECUTED, tenantId.toString(), event);
        log.info("Query executed: tenant={} latency={}ms chunks={} cacheHit={}",
                tenantId, latencyMs, chunksRetrieved, cacheHit);
    }
}
