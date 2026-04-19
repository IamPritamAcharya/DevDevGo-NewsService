package com.devdevgo.news.fetcher;

import com.devdevgo.news.model.Article;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleFetcherService {

    private final HackerNewsFetcher hackerNewsFetcher;
    private final GNewsFetcher gNewsFetcher;
    private final RedditFetcher redditFetcher;
    private final RssFetcher rssFetcher;

    public Flux<Article> fetchAll() {
        log.info("=== Starting concurrent fetch from all sources ===");

        return Flux.merge(
                hackerNewsFetcher.fetch().onErrorResume(e -> {
                    log.error("HN fetch failed entirely: {}", e.getMessage());
                    return Flux.empty();
                }),
                gNewsFetcher.fetch().onErrorResume(e -> {
                    log.error("GNews fetch failed entirely: {}", e.getMessage());
                    return Flux.empty();
                }),
                redditFetcher.fetch().onErrorResume(e -> {
                    log.error("Reddit fetch failed entirely: {}", e.getMessage());
                    return Flux.empty();
                }),
                rssFetcher.fetch().onErrorResume(e -> {
                    log.error("RSS fetch failed entirely: {}", e.getMessage());
                    return Flux.empty();
                }))
                .doOnNext(a -> log.debug("Fetched: [{}] {}", a.getSource(), a.getTitle()))
                .doOnComplete(() -> log.info("=== All sources fetched ==="));
    }
}
