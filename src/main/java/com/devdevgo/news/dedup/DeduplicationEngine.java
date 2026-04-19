package com.devdevgo.news.dedup;

import com.devdevgo.news.model.Article;
import com.devdevgo.news.storage.FirebaseStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeduplicationEngine {

    private final FirebaseStorageService storageService;

    @Value("${news.pipeline.dedup.similarity-threshold:0.7}")
    private double similarityThreshold;

    @Value("${news.pipeline.dedup.memory-hours:24}")
    private int memoryHours;

    public Mono<List<Article>> deduplicate(List<Article> articles) {
        log.info("[Dedup] Starting deduplication of {} articles", articles.size());

        return storageService.getRecentArticleMemory(memoryHours)
                .map(memory -> performDeduplication(articles, memory))
                .doOnSuccess(result -> log.info("[Dedup] Reduced to {} unique articles", result.size()));
    }

    private List<Article> performDeduplication(List<Article> articles, List<Map<String, String>> memory) {
        articles.forEach(a -> a.setNormalizedUrl(normalizeUrl(a.getUrl())));

        Set<String> seenUrls = new HashSet<>();
        List<Article> urlDeduped = new ArrayList<>();
        for (Article a : articles) {
            if (seenUrls.add(a.getNormalizedUrl())) {
                urlDeduped.add(a);
            }
        }
        log.debug("[Dedup] After URL dedup: {} articles", urlDeduped.size());
        Set<String> memoryUrls = memory.stream()
                .map(m -> m.getOrDefault("normalizedUrl", ""))
                .collect(Collectors.toSet());
        Set<String> memoryTitles = memory.stream()
                .map(m -> m.getOrDefault("normalizedTitle", "").toLowerCase())
                .collect(Collectors.toSet());

        List<Article> memoryFiltered = urlDeduped.stream()
                .filter(a -> !memoryUrls.contains(a.getNormalizedUrl()))
                .filter(a -> !memoryTitles.contains(a.getTitle().toLowerCase().trim()))
                .collect(Collectors.toList());
        log.debug("[Dedup] After 24h memory check: {} articles", memoryFiltered.size());

        List<Article> finalList = new ArrayList<>();
        for (Article candidate : memoryFiltered) {
            boolean isDuplicate = false;
            for (Article accepted : finalList) {
                double similarity = cosineSimilarity(candidate.getTitle(), accepted.getTitle());
                if (similarity > similarityThreshold) {
                    isDuplicate = true;
                    log.debug("[Dedup] Cosine dup ({:.2f}): '{}' ~ '{}'",
                            similarity, candidate.getTitle(), accepted.getTitle());
                    break;
                }
            }
            if (!isDuplicate) {
                finalList.add(candidate);
            }
        }
        log.info("[Dedup] After cosine similarity: {} unique articles", finalList.size());
        return finalList;
    }

    double cosineSimilarity(String text1, String text2) {
        Map<String, Integer> vec1 = termFrequency(tokenize(text1));
        Map<String, Integer> vec2 = termFrequency(tokenize(text2));

        Set<String> allTerms = new HashSet<>(vec1.keySet());
        allTerms.addAll(vec2.keySet());

        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        for (String term : allTerms) {
            int v1 = vec1.getOrDefault(term, 0);
            int v2 = vec2.getOrDefault(term, 0);
            dotProduct += v1 * v2;
            norm1 += v1 * v1;
            norm2 += v2 * v2;
        }

        if (norm1 == 0 || norm2 == 0)
            return 0.0;
        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    private List<String> tokenize(String text) {
        if (text == null)
            return List.of();
        return Arrays.asList(
                text.toLowerCase()
                        .replaceAll("[^a-z0-9\\s]", "")
                        .trim()
                        .split("\\s+"));
    }

    private Map<String, Integer> termFrequency(List<String> tokens) {
        Map<String, Integer> freq = new HashMap<>();
        for (String token : tokens) {
            if (!token.isBlank()) {
                freq.merge(token, 1, Integer::sum);
            }
        }
        return freq;
    }

    String normalizeUrl(String url) {
        if (url == null)
            return "";
        try {
            URI uri = new URI(url.toLowerCase().trim());
            String normalized = uri.getScheme() + "://" + uri.getHost() + uri.getPath();
            return normalized.replaceAll("/$", "");
        } catch (Exception e) {
            return url.toLowerCase().trim()
                    .replaceAll("\\?.*$", "")
                    .replaceAll("/$", "");
        }
    }
}
