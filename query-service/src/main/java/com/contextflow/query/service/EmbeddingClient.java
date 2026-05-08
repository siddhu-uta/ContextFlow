package com.contextflow.query.service;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.contextflow.query.config.QueryProperties;
import com.contextflow.query.dto.EmbedRequest;
import com.contextflow.query.dto.EmbedResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingClient {

    private final RestTemplate restTemplate;
    private final QueryProperties properties;

    public float[] embedQuery(String text) {
        String url = properties.embeddingWorkerUrl() + "/embed";
        EmbedResponse response = restTemplate.postForObject(url, new EmbedRequest(text), EmbedResponse.class);

        if (response == null || response.embedding() == null) {
            throw new IllegalStateException("Embedding worker returned empty response for query");
        }

        log.debug("Embedded query ({} chars) → {}d vector", text.length(), response.dimension());
        return response.embedding();
    }
}
