package com.devdevgo.news.summarizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${news.api.gemini-key-primary}")
    private String primaryKey;

    @Value("${news.api.gemini-key-fallback}")
    private String fallbackKey;

    private static final String GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/models";
    private static final String MODEL = "gemini-2.5-flash-lite";

    public Mono<String> generateSummary(String prompt) {
        return callGeminiWithRetry(prompt, primaryKey)
                .onErrorResume(e -> {
                    log.warn("[Gemini] Primary key exhausted after retries ({}), trying fallback key...",
                            e.getMessage());
                    return callGeminiWithRetry(prompt, fallbackKey);
                });
    }

    private Mono<String> callGeminiWithRetry(String prompt, String apiKey) {
        return callGemini(prompt, apiKey)
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(30))
                        .maxBackoff(Duration.ofHours(1))
                        .filter(e -> is429(e))
                        .doBeforeRetry(signal -> log.warn(
                                "[Gemini] 429 rate limit hit — waiting before retry #{} (backoff: {}s)...",
                                signal.totalRetries() + 1,
                                30 * (signal.totalRetries() + 1))));
    }

    private Mono<String> callGemini(String prompt, String apiKey) {
        Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of(
                        "maxOutputTokens", 200,
                        "temperature", 0.4,
                        "topP", 0.9));

        return webClient.post()
                .uri(GEMINI_BASE + "/" + MODEL + ":generateContent?key=" + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(this::extractText)
                .filter(text -> !text.isBlank())
                .switchIfEmpty(Mono.error(new RuntimeException("Empty response from Gemini")));
    }

    private boolean is429(Throwable e) {
        return e instanceof WebClientResponseException &&
                ((WebClientResponseException) e).getStatusCode() == HttpStatus.TOO_MANY_REQUESTS;
    }

    private String extractText(JsonNode response) {
        try {
            return response
                    .path("candidates")
                    .get(0)
                    .path("content")
                    .path("parts")
                    .get(0)
                    .path("text")
                    .asText("");
        } catch (Exception e) {
            log.warn("[Gemini] Failed to extract text from response: {}", e.getMessage());
            return "";
        }
    }
}