package com.devdevgo.news.fetcher;

import com.devdevgo.news.model.Article;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.time.format.DateTimeFormatter;


@Slf4j
@Component
@RequiredArgsConstructor
public class GNewsFetcher {

    private final WebClient webClient;

    @Value("${news.api.gnews-key}")
    private String apiKey;

    @Value("${news.pipeline.gnews.max:50}")
    private int maxArticles;

    private static final String GNEWS_BASE = "https://gnews.io/api/v4";

    public Flux<Article> fetch() {
        log.info("[GNews] Fetching up to {} articles...", maxArticles);
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme("https")
                        .host("gnews.io")
                        .path("/api/v4/top-headlines")
                        .queryParam("category", "technology")
                        .queryParam("lang", "en")
                        .queryParam("max", maxArticles)
                        .queryParam("apikey", apiKey)
                        .build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .flatMapMany(root -> {
                    JsonNode articles = root.path("articles");
                    if (!articles.isArray()) return Flux.empty();
                    return Flux.fromIterable(articles)
                            .mapNotNull(this::parseArticle);
                })
                .doOnComplete(() -> log.info("[GNews] Fetch complete"))
                .onErrorResume(e -> {
                    log.error("[GNews] Failed to fetch: {}", e.getMessage());
                    return Flux.empty();
                });
    }

    private Article parseArticle(JsonNode node) {
        String title = node.path("title").asText("").trim();
        String url = node.path("url").asText("").trim();
        if (title.isBlank() || url.isBlank()) return null;

        String description = node.path("description").asText("");
        String imageUrl = node.path("image").asText("");
        String publishedStr = node.path("publishedAt").asText("");

        Instant publishedAt;
        try {
            publishedAt = Instant.parse(publishedStr);
        } catch (Exception e) {
            publishedAt = Instant.now();
        }

        JsonNode sourceNode = node.path("source");
        String sourceName = sourceNode.path("name").asText("gnews");
        String sourceUrl = sourceNode.path("url").asText("");
        String domain = extractDomain(sourceUrl.isBlank() ? url : sourceUrl);

        return Article.builder()
                .title(title)
                .url(url)
                .source("gnews")
                .sourceDomain(domain)
                .description(description)
                .imageUrl(imageUrl)
                .publishedAt(publishedAt)
                .build();
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
