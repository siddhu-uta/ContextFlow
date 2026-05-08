package com.contextflow.query.service;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.extern.slf4j.Slf4j;

/**
 * Mock LLM that simulates streaming without an API key.
 * Sends a canned response word-by-word with a small delay.
 * Switch to real OpenAI by setting query.llm-mode=openai.
 */
@Service
@ConditionalOnProperty(name = "query.llm-mode", havingValue = "mock", matchIfMissing = true)
@Slf4j
public class MockStreamingService implements LlmStreamingService {

    private static final String MOCK_ANSWER =
            "Based on the documents provided, here is a summary relevant to your question. "
            + "This is a mock response — the actual answer would come from GPT-4o-mini using "
            + "the retrieved context chunks shown in the sources above. "
            + "Set query.llm-mode=openai and provide OPENAI_API_KEY to enable real LLM responses.";

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Override
    public void stream(String systemPrompt, String userQuestion,
                       SseEmitter emitter, StringBuilder accumulator, Runnable onComplete) {
        executor.submit(() -> {
            try {
                for (String word : MOCK_ANSWER.split("(?<=\\s)|(?=\\s)")) {
                    accumulator.append(word);
                    emitter.send(SseEmitter.event().name("token").data(Map.of("text", word)));
                    Thread.sleep(40);
                }
                emitter.send(SseEmitter.event().name("done").data(Map.of()));
                emitter.complete();
                onComplete.run();
            } catch (IOException | InterruptedException e) {
                emitter.completeWithError(e);
            }
        });
    }
}
