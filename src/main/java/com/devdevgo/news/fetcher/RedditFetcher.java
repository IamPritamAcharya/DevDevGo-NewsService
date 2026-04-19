package com.devdevgo.news.fetcher;

import com.devdevgo.news.model.Article;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;


@Slf4j
@Component
@RequiredArgsConstructor
public class RedditFetcher {

    private final WebClient webClient;

    @Value("${news.pipeline.reddit.min-upvotes:100}")
    private int minUpvotes;

    @Value("${news.pipeline.reddit.min-comments:20}")
    private int minComments;

    private static final List<String[]> ENDPOINTS = List.of(
            new String[] { "r/programming/hot", "https://www.reddit.com/r/programming/hot.json?limit=50" },
            new String[] { "r/programming/new", "https://www.reddit.com/r/programming/new.json?limit=50" },
            new String[] { "r/technology/top", "https://www.reddit.com/r/technology/top.json?limit=50&t=day" },
            new String[] { "r/technology/hot", "https://www.reddit.com/r/technology/hot.json?limit=50" });

    public Flux<Article> fetch() {
        log.info("[Reddit] Fetching from {} public endpoints...", ENDPOINTS.size());
        return Flux.fromIterable(ENDPOINTS)
                .flatMap(endpoint -> fetchEndpoint(endpoint[0], endpoint[1]), 2)
                .doOnComplete(() -> log.info("[Reddit] Fetch complete"))
                .onErrorResume(e -> {
                    log.error("[Reddit] Failed: {}", e.getMessage());
                    return Flux.empty();
                });
    }

    private Flux<Article> fetchEndpoint(String label, String url) {
        return webClient.get()
                .uri(url)
                .header(HttpHeaders.USER_AGENT, "NewsAggregator/1.0 (by /u/newsbot)")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .flatMapMany(root -> {
                    JsonNode children = root.path("data").path("children");
                    if (!children.isArray())
                        return Flux.empty();
                    return Flux.fromIterable(children)
                            .mapNotNull(child -> parsePost(child.path("data"), label));
                })
                .onErrorResume(e -> {
                    log.warn("[Reddit] Failed {}: {}", label, e.getMessage());
                    return Flux.empty();
                });
    }

    private Article parsePost(JsonNode data, String label) {
        String title = data.path("title").asText("").trim();
        String url = data.path("url").asText("").trim();
        int upvotes = data.path("score").asInt(0);
        int comments = data.path("num_comments").asInt(0);
        boolean isSelf = data.path("is_self").asBoolean(true);

        if (isSelf || url.contains("reddit.com"))
            return null;
        if (upvotes < minUpvotes)
            return null;
        if (comments < minComments)
            return null;
        if (title.isBlank() || url.isBlank())
            return null;

        long created = data.path("created_utc").asLong(0);
        String thumbnail = data.path("thumbnail").asText("");
        String subreddit = data.path("subreddit_name_prefixed").asText(label);

        return Article.builder()
                .title(title)
                .url(url)
                .source("reddit")
                .sourceDomain(extractDomain(url))
                .description(subreddit + " — " + upvotes + " upvotes, " + comments + " comments")
                .imageUrl(thumbnail.startsWith("http") ? thumbnail : "")
                .publishedAt(Instant.ofEpochSecond(created))
                .upvotes(upvotes)
                .comments(comments)
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