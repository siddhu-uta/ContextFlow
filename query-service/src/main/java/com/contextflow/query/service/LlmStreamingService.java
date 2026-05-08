package com.contextflow.query.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Abstraction over LLM providers.
 * Switch between "openai" and "mock" via query.llm-mode in application.yml.
 */
public interface LlmStreamingService {

    /**
     * Streams an LLM response token by token over the SSE emitter.
     *
     * @param systemPrompt  RAG context + instructions assembled from retrieved chunks
     * @param userQuestion  the original user question
     * @param emitter       open SSE connection to the client
     * @param accumulator   collects the full response for caching after streaming ends
     * @param onComplete    called after the last token is sent (cache write, Kafka event)
     */
    void stream(String systemPrompt, String userQuestion,
                SseEmitter emitter, StringBuilder accumulator, Runnable onComplete);
}
