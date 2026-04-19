package com.devdevgo.news.ranking;

import com.devdevgo.news.model.Article;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Ranking Engine — scores and selects top 30 articles.
 *
 * Scoring factors:
 *   1. Recency Score     (0–10) weight: 0.4
 *   2. Source Score      (0–10) weight: 0.2
 *   3. Engagement Score  (0–10) weight: 0.2
 *   4. Title Quality     (-5 to +5) weight: 0.1
 *   5. Keyword Boost     (0–3) weight: 0.1
 *
 * Final Score = (Recency × 0.4) + (Source × 0.2) + (Engagement × 0.2)
 *             + (Title × 0.1) + (Keyword × 0.1)
 *
 * Diversity filter: max 5 articles per topic domain.
 * Output: top 30 articles.
 */
@Slf4j
@Service
public class RankingEngine {

    @Value("${news.pipeline.top-articles:30}")
    private int topArticles;

    @Value("${news.pipeline.diversity.max-per-topic:5}")
    private int maxPerTopic;

    private static final Map<String, Double> SOURCE_SCORES = Map.ofEntries(
            Map.entry("techcrunch.com", 9.0),
            Map.entry("theverge.com", 8.5),
            Map.entry("arstechnica.com", 9.0),
            Map.entry("wired.com", 8.5),
            Map.entry("thenextweb.com", 7.5),
            Map.entry("zdnet.com", 7.0),
            Map.entry("cnet.com", 7.0),
            Map.entry("engadget.com", 7.5),
            Map.entry("venturebeat.com", 8.0),
            Map.entry("bloomberg.com", 9.0),
            Map.entry("reuters.com", 8.5),
            Map.entry("github.com", 8.0),
            Map.entry("medium.com", 6.0),
            Map.entry("dev.to", 6.5),
            Map.entry("hackernoon.com", 6.5),
            Map.entry("infoq.com", 7.5),
            Map.entry("openai.com", 9.0),
            Map.entry("anthropic.com", 9.0),
            Map.entry("google.com", 8.5),
            Map.entry("microsoft.com", 8.0)
    );

    // Trending tech keyword boosts
    private static final List<String> TRENDING_KEYWORDS = List.of(
            "ai", "gpt", "llm", "claude", "gemini", "openai", "anthropic",
            "flutter", "dart", "kotlin", "swift", "rust", "go", "python",
            "kubernetes", "docker", "cloud", "aws", "gcp", "azure",
            "machine learning", "deep learning", "neural", "transformer",
            "open source", "github", "apple", "google", "microsoft",
            "android", "ios", "react", "typescript", "java", "spring",
            "security", "vulnerability", "breach", "crypto", "blockchain",
            "startup", "funding", "acquisition", "ipo"
    );

    // Clickbait patterns to penalize
    private static final List<String> CLICKBAIT_PATTERNS = List.of(
            "you won't believe", "shocking", "this one trick",
            "click here", "must see", "jaw-dropping", "unbelievable",
            "blown away", "incredible secret"
    );

    public List<Article> rank(List<Article> articles) {
        log.info("[Ranking] Scoring {} articles...", articles.size());

        Instant now = Instant.now();

        // Score each article
        articles.forEach(a -> {
            a.setRecencyScore(computeRecencyScore(a, now));
            a.setSourceScore(computeSourceScore(a));
            a.setEngagementScore(computeEngagementScore(a));
            a.setTitleQualityScore(computeTitleQualityScore(a));
            a.setKeywordBoost(computeKeywordBoost(a));

            double finalScore =
                    (a.getRecencyScore() * 0.4) +
                    (a.getSourceScore() * 0.2) +
                    (a.getEngagementScore() * 0.2) +
                    (a.getTitleQualityScore() * 0.1) +
                    (a.getKeywordBoost() * 0.1);

            a.setFinalScore(finalScore);

            // Derive tags
            a.setTags(deriveTags(a));
        });

        // Sort by final score descending
        List<Article> sorted = articles.stream()
                .sorted(Comparator.comparingDouble(Article::getFinalScore).reversed())
                .collect(Collectors.toList());

        // Apply diversity filter: max maxPerTopic per source domain
        List<Article> diverse = applyDiversityFilter(sorted);

        // Select top N
        List<Article> top = diverse.stream()
                .limit(topArticles)
                .collect(Collectors.toList());

        log.info("[Ranking] Selected top {} articles", top.size());
        return top;
    }

    /**
     * Recency Score (0–10): linear decay based on article age.
     * 0h = 10, 12h = 5, 24h+ = 0
     */
    private double computeRecencyScore(Article article, Instant now) {
        if (article.getPublishedAt() == null) return 0.0;
        long ageHours = Duration.between(article.getPublishedAt(), now).toHours();
        if (ageHours < 0) ageHours = 0;
        if (ageHours >= 24) return 0.0;
        return Math.max(0.0, 10.0 - (ageHours * 10.0 / 24.0));
    }

    /**
     * Source Score (0–10): based on trusted domain list.
     * Default: 5.0 for unknown sources.
     */
    private double computeSourceScore(Article article) {
        String domain = article.getSourceDomain();
        if (domain == null || domain.isBlank()) return 5.0;
        return SOURCE_SCORES.getOrDefault(domain.toLowerCase(), 5.0);
    }

    /**
     * Engagement Score (0–10): log-scaled upvotes + comments.
     */
    private double computeEngagementScore(Article article) {
        int total = article.getUpvotes() + article.getComments();
        if (total <= 0) return 3.0; // neutral baseline for sources without engagement metrics
        // log1p to compress the range: ~100 total → 4.6, ~1000 → 6.9, ~10000 → 9.2
        double logScore = Math.log1p(total);
        return Math.min(10.0, logScore * 10.0 / Math.log1p(10000));
    }

    /**
     * Title Quality Score (-5 to +5).
     * Penalize: clickbait, very short titles.
     * Reward: clear technical titles.
     */
    private double computeTitleQualityScore(Article article) {
        String title = article.getTitle();
        if (title == null) return 0.0;

        double score = 2.0; // baseline

        // Penalize clickbait
        String titleLower = title.toLowerCase();
        for (String pattern : CLICKBAIT_PATTERNS) {
            if (titleLower.contains(pattern)) {
                score -= 3.0;
                break;
            }
        }

        // Penalize very short titles (< 20 chars)
        if (title.length() < 20) score -= 2.0;

        // Reward clear technical titles (30-100 chars is ideal)
        if (title.length() >= 30 && title.length() <= 100) score += 2.0;

        // Reward titles with numbers/versions (often more specific)
        if (title.matches(".*\\d+.*")) score += 0.5;

        return Math.max(-5.0, Math.min(5.0, score));
    }

    /**
     * Keyword Boost (0–3): presence of trending tech topics.
     */
    private double computeKeywordBoost(Article article) {
        String combined = ((article.getTitle() != null ? article.getTitle() : "") + " " +
                (article.getDescription() != null ? article.getDescription() : "")).toLowerCase();

        long matches = TRENDING_KEYWORDS.stream()
                .filter(combined::contains)
                .count();

        return Math.min(3.0, matches * 0.5);
    }

    /**
     * Diversity filter: no more than maxPerTopic articles from same domain.
     */
    private List<Article> applyDiversityFilter(List<Article> sorted) {
        Map<String, Integer> domainCount = new HashMap<>();
        List<Article> result = new ArrayList<>();

        for (Article article : sorted) {
            String domain = article.getSourceDomain() != null ? article.getSourceDomain() : "unknown";
            int count = domainCount.getOrDefault(domain, 0);
            if (count < maxPerTopic) {
                result.add(article);
                domainCount.put(domain, count + 1);
            }
        }
        return result;
    }

    /**
     * Derive tags from title and description keywords.
     */
    private List<String> deriveTags(Article article) {
        String combined = ((article.getTitle() != null ? article.getTitle() : "") + " " +
                (article.getDescription() != null ? article.getDescription() : "")).toLowerCase();

        List<String> tags = new ArrayList<>();
        List<String> tagKeywords = List.of(
                "AI", "Machine Learning", "Flutter", "Python", "JavaScript",
                "TypeScript", "Rust", "Go", "Java", "Kubernetes", "Docker",
                "Cloud", "Security", "Open Source", "Android", "iOS",
                "React", "LLM", "GPT", "Blockchain", "Crypto", "Startup"
        );

        for (String keyword : tagKeywords) {
            if (combined.contains(keyword.toLowerCase())) {
                tags.add(keyword);
            }
        }

        // Add source as a tag
        if (article.getSource() != null) {
            tags.add(article.getSource());
        }

        return tags.stream().distinct().limit(5).collect(Collectors.toList());
    }
}
