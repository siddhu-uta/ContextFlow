package com.contextflow.query.service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.contextflow.query.config.QueryProperties;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.message.AiMessage;
import lombok.extern.slf4j.Slf4j;

@Service
@ConditionalOnProperty(name = "query.llm-mode", havingValue = "openai")
@Slf4j
public class OpenAiStreamingService implements LlmStreamingService {

    private final OpenAiStreamingChatModel model;

    public OpenAiStreamingService(QueryProperties props) {
        this.model = OpenAiStreamingChatModel.builder()
                .apiKey(props.openAiApiKey())
                .modelName(props.openAiModel())
                .temperature(0.3)   // Lower temp = more factual, better for RAG
                .build();
    }

    @Override
    public void stream(String systemPrompt, String userQuestion,
                       SseEmitter emitter, StringBuilder accumulator, Runnable onComplete) {
        model.generate(
                List.of(SystemMessage.from(systemPrompt), UserMessage.from(userQuestion)),
                new StreamingResponseHandler<AiMessage>() {

                    @Override
                    public void onNext(String token) {
                        accumulator.append(token);
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("token")
                                    .data(Map.of("text", token)));
                        } catch (IOException e) {
                            emitter.completeWithError(e);
                        }
                    }

                    @Override
                    public void onComplete(Response<AiMessage> response) {
                        try {
                            emitter.send(SseEmitter.event().name("done").data(Map.of()));
                            emitter.complete();
                            onComplete.run();
                        } catch (IOException e) {
                            emitter.completeWithError(e);
                        }
                        log.info("LLM streaming complete. Tokens: {}", accumulator.length());
                    }

                    @Override
                    public void onError(Throwable error) {
                        log.error("LLM streaming error: {}", error.getMessage());
                        emitter.completeWithError(error);
                    }
                }
        );
    }
}
