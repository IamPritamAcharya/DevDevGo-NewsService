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

    @Value("${news.api.gemini-key-fallback1}")
    private String fallbackKey1;

    @Value("${news.api.gemini-key-fallback2}")
    private String fallbackKey2;

    @Value("${news.api.gemini-key-fallback3}")
    private String fallbackKey3;

    private static final String GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/models";
    private static final String MODEL = "gemini-2.5-flash-lite";

    /**
     * Tries each API key in sequence with limited retries per key.
     * If a key fails with 429 after retries, moves on to the next.
     * If ALL keys are exhausted, propagates a terminal error — no further looping.
     */
    public Mono<String> generateSummary(String prompt) {
        return callWithLimitedRetry(prompt, primaryKey, "primary")
                .onErrorResume(AllKeysExhaustedException.class, e -> Mono.error(e)) // already done
                .onErrorResume(e -> !(e instanceof AllKeysExhaustedException), e -> {
                    log.warn("[Gemini] Primary key failed ({}), trying fallback-1...", e.getMessage());
                    return callWithLimitedRetry(prompt, fallbackKey1, "fallback-1");
                })
                .onErrorResume(e -> !(e instanceof AllKeysExhaustedException), e -> {
                    log.warn("[Gemini] Fallback-1 failed ({}), trying fallback-2...", e.getMessage());
                    return callWithLimitedRetry(prompt, fallbackKey2, "fallback-2");
                })
                .onErrorResume(e -> !(e instanceof AllKeysExhaustedException), e -> {
                    log.warn("[Gemini] Fallback-2 failed ({}), trying fallback-3...", e.getMessage());
                    return callWithLimitedRetry(prompt, fallbackKey3, "fallback-3")
                            .onErrorMap(ex -> {
                                log.error("[Gemini] All 4 API keys exhausted. Giving up.");
                                return new AllKeysExhaustedException("All Gemini API keys exhausted", ex);
                            });
                });
    }

    /**
     * Retries a single key up to 3 times on 429, with exponential backoff capped at
     * 2 minutes.
     * Non-429 errors are NOT retried — they propagate immediately to trigger the
     * next key.
     */
    private Mono<String> callWithLimitedRetry(String prompt, String apiKey, String keyLabel) {
        return callGemini(prompt, apiKey)
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(30))
                        .maxBackoff(Duration.ofMinutes(2))
                        .filter(e -> is429(e))
                        .doBeforeRetry(signal -> log.warn(
                                "[Gemini][{}] 429 rate limit — retry #{} of 3",
                                keyLabel, signal.totalRetries() + 1))
                        // After 3 retries all gave 429, wrap so the next key is tried
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

    /**
     * Sentinel exception to signal all keys are done — prevents further
     * onErrorResume chains.
     */
    public static class AllKeysExhaustedException extends RuntimeException {
        public AllKeysExhaustedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}