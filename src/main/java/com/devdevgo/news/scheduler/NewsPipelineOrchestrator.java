package com.devdevgo.news.scheduler;

import com.devdevgo.news.dedup.DeduplicationEngine;
import com.devdevgo.news.fetcher.ArticleFetcherService;
import com.devdevgo.news.model.Article;
import com.devdevgo.news.model.NewsArticle;
import com.devdevgo.news.model.SystemState;
import com.devdevgo.news.ranking.RankingEngine;
import com.devdevgo.news.storage.FirebaseStorageService;
import com.devdevgo.news.summarizer.SummarizationEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class NewsPipelineOrchestrator {

    private final ArticleFetcherService fetcherService;
    private final DeduplicationEngine deduplicationEngine;
    private final RankingEngine rankingEngine;
    private final SummarizationEngine summarizationEngine;
    private final FirebaseStorageService storageService;

    public Mono<Integer> execute() {
        log.info("╔══════════════════════════════════════════════════");
        log.info("║  NEWS PIPELINE STARTED @ {}", Instant.now());
        log.info("╚══════════════════════════════════════════════════");

        AtomicInteger savedCount = new AtomicInteger(0);
        List<NewsArticle> allSaved = new ArrayList<>();

        return fetcherService.fetchAll()
                .collectList()
                .flatMap(rawArticles -> {
                    log.info("[Pipeline] Fetched {} raw articles", rawArticles.size());
                    if (rawArticles.isEmpty()) {
                        return Mono.error(new RuntimeException("No articles fetched from any source"));
                    }
                    return deduplicationEngine.deduplicate(rawArticles);
                })
                .map(uniqueArticles -> {
                    log.info("[Pipeline] After dedup: {} articles", uniqueArticles.size());
                    List<Article> ranked = rankingEngine.rank(uniqueArticles);
                    log.info("[Pipeline] After ranking: {} articles selected", ranked.size());
                    return ranked;
                })
                .flatMapMany(rankedArticles ->

                summarizationEngine.summarize(rankedArticles))
                .flatMap(article -> storageService.saveArticles(List.of(article))
                        .doOnSuccess(count -> {
                            int total = savedCount.addAndGet(count);
                            allSaved.add(article);
                            log.info("[Pipeline] Saved article {}/{}: '{}'",
                                    total, "30", article.getTitle());
                        })
                        .onErrorResume(e -> {

                            log.error("[Pipeline] Failed to save '{}': {}", article.getTitle(), e.getMessage());
                            return Mono.just(0);
                        }), 1)
                .collectList()
                .flatMap(results -> {
                    int total = savedCount.get();
                    log.info("[Pipeline] All done — {} articles saved to Firebase", total);

                    if (total == 0) {
                        return updateStateFailure("0 articles saved").thenReturn(-1);
                    }

                    return storageService.saveToDeduplicationMemory(allSaved)
                            .then(updateStateSuccess(total))
                            .thenReturn(total);
                })
                .doOnSuccess(count -> {
                    log.info("╔══════════════════════════════════════════════════");
                    log.info("║  PIPELINE COMPLETE — {} articles stored", count);
                    log.info("╚══════════════════════════════════════════════════");
                })
                .onErrorResume(e -> {
                    log.error("╔══════════════════════════════════════════════════");
                    log.error("║  PIPELINE FAILED: {}", e.getMessage());
                    log.error("╚══════════════════════════════════════════════════");
                    int partial = savedCount.get();
                    if (partial > 0) {
                        log.info("[Pipeline] Partial success — {} articles were already saved", partial);
                        return updateStateSuccess(partial).thenReturn(partial);
                    }
                    return updateStateFailure(e.getMessage()).thenReturn(-1);
                });
    }

    private Mono<Void> updateStateSuccess(int count) {
        return storageService.updateSystemState(
                SystemState.builder()
                        .lastFetchedAt(Instant.now())
                        .lastRunStatus("success")
                        .lastArticleCount(count)
                        .build());
    }

    private Mono<Void> updateStateFailure(String reason) {
        return storageService.updateSystemState(
                SystemState.builder()
                        .lastFetchedAt(Instant.now())
                        .lastRunStatus("failed: " + reason)
                        .lastArticleCount(0)
                        .build())
                .onErrorResume(e -> {
                    log.error("Failed to update system state: {}", e.getMessage());
                    return Mono.empty();
                });
    }
}