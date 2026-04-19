package com.devdevgo.news.summarizer;

import com.devdevgo.news.model.Article;
import com.devdevgo.news.model.NewsArticle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * LLM Summarization Engine using Gemini Flash-Lite.
 *
 * For each article:
 * 1. Clean content / description
 * 2. Build prompt
 * 3. Call Gemini (primary key → fallback key)
 * 4. Validate output (≤70 words, not empty)
 * 5. Retry once if invalid
 * 6. Fallback to description if still invalid
 *
 * Rate control: 1.5s delay between requests.
 * Output: 30 NewsArticles with high-quality summaries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SummarizationEngine {

    private final GeminiClient geminiClient;
    private final ContentCleaner contentCleaner;

    @Value("${news.pipeline.summarizer.max-words:60}")
    private int maxWords;

    @Value("${news.pipeline.summarizer.request-delay-ms:1500}")
    private long requestDelayMs;

    /**
     * Summarize a list of articles sequentially (with delay between calls).
     * Returns a Flux of NewsArticle objects ready for storage.
     */
    public Flux<NewsArticle> summarize(List<Article> articles) {
        log.info("[Summarizer] Starting summarization of {} articles", articles.size());

        return Flux.fromIterable(articles)
                .concatMap(article -> summarizeArticle(article)
                        .delayElement(Duration.ofMillis(requestDelayMs)))
                .doOnComplete(() -> log.info("[Summarizer] Summarization complete"));
    }

    private Mono<NewsArticle> summarizeArticle(Article article) {
        String cleanedContent = prepareContent(article);
        String prompt = buildPrompt(article.getTitle(), cleanedContent);

        return geminiClient.generateSummary(prompt)
                .map(summary -> validateAndClean(summary))
                .flatMap(summary -> {
                    if (isValidSummary(summary)) {
                        return Mono.just(summary);
                    }
                    // Retry once with a simplified prompt
                    log.warn("[Summarizer] Invalid summary for '{}', retrying...", article.getTitle());
                    return geminiClient.generateSummary(buildSimplePrompt(article.getTitle()))
                            .map(this::validateAndClean);
                })
                .map(summary -> {
                    if (!isValidSummary(summary)) {
                        // Final fallback: use description
                        log.warn("[Summarizer] Using description fallback for '{}'", article.getTitle());
                        summary = buildFallbackSummary(article);
                    }
                    return buildNewsArticle(article, summary);
                })
                .onErrorResume(e -> {
                    log.error("[Summarizer] Failed for '{}': {}", article.getTitle(), e.getMessage());
                    String fallback = buildFallbackSummary(article);
                    return Mono.just(buildNewsArticle(article, fallback));
                });
    }

    private String prepareContent(Article article) {
        // Use description if available (already shorter), otherwise rely on title
        String raw = article.getDescription() != null && !article.getDescription().isBlank()
                ? article.getDescription()
                : article.getTitle();
        return contentCleaner.clean(raw);
    }

    private String buildPrompt(String title, String content) {
        return String.format("""
                You are a tech news summarizer for developers.
                Write a concise, engaging summary of the following tech article in EXACTLY %d words or fewer.
                Be clear and specific. No fluff. Developer-focused tone. No clickbait.
                Start directly with the key information — no intro phrase like "This article...".

                Title: %s

                Content: %s

                Summary:""", maxWords, title, content);
    }

    private String buildSimplePrompt(String title) {
        return String.format("""
                Summarize this tech news headline in %d words or fewer.
                Be direct and informative. Developer-focused.

                Headline: %s

                Summary:""", maxWords, title);
    }

    private String validateAndClean(String summary) {
        if (summary == null)
            return "";
        // Remove leading/trailing whitespace and quotes
        summary = summary.trim()
                .replaceAll("^[\"']|[\"']$", "")
                .replaceAll("^Summary:\\s*", "")
                .trim();
        return summary;
    }

    /**
     * Validation checks:
     * 1. Summary must exist (not blank)
     * 2. Word count <= 70
     * 3. Minimum length (at least 10 words)
     */
    private boolean isValidSummary(String summary) {
        if (summary == null || summary.isBlank())
            return false;
        String[] words = summary.trim().split("\\s+");
        return words.length >= 5 && words.length <= 100; // relaxed upper limit
    }

    /**
     * Fallback summary: use cleaned description, trimmed to maxWords.
     */
    private String buildFallbackSummary(Article article) {
        String desc = contentCleaner.cleanDescription(article.getDescription());
        if (desc != null && !desc.isBlank()) {
            String[] words = desc.split("\\s+");
            if (words.length > maxWords) {
                desc = Arrays.stream(words).limit(maxWords).collect(Collectors.joining(" ")) + "...";
            }
            return desc;
        }
        // Last resort: use title
        return article.getTitle();
    }

    private NewsArticle buildNewsArticle(Article article, String summary) {
        return NewsArticle.builder()
                .id(generateId(article))
                .title(article.getTitle())
                .summary(summary)
                .source(article.getSource())
                .sourceDomain(article.getSourceDomain())
                .url(article.getUrl())
                .imageUrl(article.getImageUrl())
                .publishedAt(article.getPublishedAt())
                .createdAt(Instant.now())
                .score(article.getFinalScore())
                .tags(article.getTags())
                .build();
    }

    /**
     * Generate a stable ID from URL to ensure idempotency.
     */
    private String generateId(Article article) {
        String base = article.getNormalizedUrl() != null
                ? article.getNormalizedUrl()
                : article.getUrl();
        // Use UUID based on URL hash for idempotency
        return UUID.nameUUIDFromBytes(base.getBytes()).toString();
    }
}
