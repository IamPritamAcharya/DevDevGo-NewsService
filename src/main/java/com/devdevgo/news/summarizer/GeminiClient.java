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

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${news.api.gemini-key-primary}")
    private String primaryKey;

    @Value("${news.api.gemini-key-fallback1}")
    private String fallbackKey1;

    @Value("${news.api.gemini-key-fallback2}")
    private String fallbackKey2;

    @Value("${news.api.gemini-key-fallback3}")
    private String fallbackKey3;

    private static final String GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/models";
    private static final String MODEL = "gemini-2.5-flash-lite";

    // Tracks which key we're currently on. Moves forward only, never back.
    private final AtomicInteger currentKeyIndex = new AtomicInteger(0);
    private List<String> keys;
    private List<String> keyLabels;

    @PostConstruct
    public void init() {
        keys = new ArrayList<>();
        keys.add(primaryKey);
        keys.add(fallbackKey1);
        keys.add(fallbackKey2);
        keys.add(fallbackKey3);

        keyLabels = List.of("primary", "fallback-1", "fallback-2", "fallback-3");
    }

    /**
     * Uses the current active key. If it fails after 3 retries (429) or immediately
     * (403/other),
     * permanently advances to the next key and tries that. Never goes back to a
     * previous key.
     * If all keys are exhausted, throws AllKeysExhaustedException.
     */
    public Mono<String> generateSummary(String prompt) {
        return tryFromCurrentKey(prompt);
    }

    private Mono<String> tryFromCurrentKey(String prompt) {
        int index = currentKeyIndex.get();

        if (index >= keys.size()) {
            log.error("[Gemini] All {} keys exhausted — cannot summarize", keys.size());
            return Mono.error(new AllKeysExhaustedException("All Gemini API keys exhausted"));
        }

        String apiKey = keys.get(index);
        String label = keyLabels.get(index);

        return callWithLimitedRetry(prompt, apiKey, label)
                .onErrorResume(e -> {
                    if (e instanceof AllKeysExhaustedException) {
                        return Mono.error(e);
                    }
                    // Advance to next key permanently
                    int next = currentKeyIndex.incrementAndGet();
                    log.warn("[Gemini] Key [{}] exhausted ({}). Moving permanently to key index {}.",
                            label, e.getMessage(), next);

                    if (next >= keys.size()) {
                        log.error("[Gemini] All keys exhausted after failing on [{}]", label);
                        return Mono.error(new AllKeysExhaustedException("All Gemini API keys exhausted"));
                    }

                    // Recurse with the next key
                    return tryFromCurrentKey(prompt);
                });
    }

    /**
     * Retries a single key up to 3 times on 429 with exponential backoff.
     * Non-429 errors (403, 500, etc.) propagate immediately without retry.
     */
    private Mono<String> callWithLimitedRetry(String prompt, String apiKey, String keyLabel) {
        return callGemini(prompt, apiKey)
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(30))
                        .maxBackoff(Duration.ofMinutes(2))
                        .filter(e -> is429(e))
                        .doBeforeRetry(signal -> log.warn(
                                "[Gemini][{}] 429 rate limit — retry #{} of 3",
                                keyLabel, signal.totalRetries() + 1))
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
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

    public static class AllKeysExhaustedException extends RuntimeException {
        public AllKeysExhaustedException(String message) {
            super(message);
        }
    }
}