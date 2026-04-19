package com.devdevgo.news.fetcher;

import com.devdevgo.news.model.Article;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class HackerNewsFetcher {

    private final WebClient webClient;

    @Value("${news.pipeline.hn.min-score:50}")
    private int minScore;

    @Value("${news.pipeline.hn.top-stories:100}")
    private int topStories;

    private static final String HN_BASE = "https://hacker-news.firebaseio.com/v0";

    public Flux<Article> fetch() {
        log.info("[HN] Fetching top {} stories...", topStories);
        return webClient.get()
                .uri(HN_BASE + "/topstories.json")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .flatMapMany(node -> {
                    List<Long> ids = new java.util.ArrayList<>();
                    if (node.isArray()) {
                        int limit = Math.min(topStories, node.size());
                        for (int i = 0; i < limit; i++) {
                            ids.add(node.get(i).asLong());
                        }
                    }
                    return Flux.fromIterable(ids);
                })
                .flatMap(id -> fetchStory(id), 10) // concurrency = 10
                .filter(a -> a != null)
                .doOnComplete(() -> log.info("[HN] Fetch complete"))
                .onErrorResume(e -> {
                    log.error("[HN] Failed to fetch: {}", e.getMessage());
                    return Flux.empty();
                });
    }

    private Mono<Article> fetchStory(Long id) {
        return webClient.get()
                .uri(HN_BASE + "/item/" + id + ".json")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .mapNotNull(node -> {
                    if (node == null || node.isNull()) return null;
                    String type = node.path("type").asText();
                    if (!"story".equals(type)) return null;

                    int score = node.path("score").asInt(0);
                    if (score < minScore) return null;

                    String url = node.path("url").asText("");
                    if (url.isBlank()) return null;

                    String title = node.path("title").asText("").trim();
                    if (title.isBlank()) return null;

                    long timeEpoch = node.path("time").asLong(0);
                    int comments = node.path("descendants").asInt(0);

                    return Article.builder()
                            .title(title)
                            .url(url)
                            .source("hackernews")
                            .sourceDomain(extractDomain(url))
                            .publishedAt(Instant.ofEpochSecond(timeEpoch))
                            .upvotes(score)
                            .comments(comments)
                            .build();
                })
                .onErrorResume(e -> Mono.empty());
    }

    private String extractDomain(String url) {
        try {
            java.net.URI uri = new java.net.URI(url);
            String host = uri.getHost();
            return host != null ? host.replaceFirst("^www\\.", "") : "";
        } catch (Exception e) {
            return "";
        }
    }
}
