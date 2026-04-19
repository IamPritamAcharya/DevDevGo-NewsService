package com.devdevgo.news.fetcher;

import com.devdevgo.news.model.Article;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.net.URL;
import java.time.Instant;
import java.util.List;

/**
 * Fetches articles from premium RSS feeds.
 * Sources: TechCrunch, The Verge, Ars Technica
 * Filter: published within last 12 hours.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RssFetcher {

    @Value("${news.pipeline.rss.max-age-hours:12}")
    private int maxAgeHours;

    private static final List<String> RSS_FEEDS = List.of(
            "https://techcrunch.com/feed/",
            "https://www.theverge.com/rss/index.xml",
            "https://feeds.arstechnica.com/arstechnica/index"
    );

    public Flux<Article> fetch() {
        log.info("[RSS] Fetching from {} RSS feeds...", RSS_FEEDS.size());
        return Flux.fromIterable(RSS_FEEDS)
                .flatMap(feedUrl -> fetchFeed(feedUrl).subscribeOn(Schedulers.boundedElastic()), 3)
                .doOnComplete(() -> log.info("[RSS] Fetch complete"))
                .onErrorResume(e -> {
                    log.error("[RSS] Failed: {}", e.getMessage());
                    return Flux.empty();
                });
    }

    private Flux<Article> fetchFeed(String feedUrl) {
        return Flux.defer(() -> {
            try {
                Instant cutoff = Instant.now().minusSeconds(maxAgeHours * 3600L);
                SyndFeedInput input = new SyndFeedInput();
                SyndFeed feed = input.build(new XmlReader(new URL(feedUrl)));
                String sourceName = extractDomain(feedUrl);

                return Flux.fromIterable(feed.getEntries())
                        .mapNotNull(entry -> parseEntry(entry, sourceName, feedUrl, cutoff));
            } catch (Exception e) {
                log.warn("[RSS] Failed to fetch {}: {}", feedUrl, e.getMessage());
                return Flux.empty();
            }
        });
    }

    private Article parseEntry(SyndEntry entry, String sourceName, String feedUrl, Instant cutoff) {
        String title = entry.getTitle() != null ? entry.getTitle().trim() : "";
        String url = entry.getLink() != null ? entry.getLink().trim() : "";
        if (title.isBlank() || url.isBlank()) return null;

        Instant publishedAt = entry.getPublishedDate() != null
                ? entry.getPublishedDate().toInstant()
                : entry.getUpdatedDate() != null
                ? entry.getUpdatedDate().toInstant()
                : Instant.now();

        if (publishedAt.isBefore(cutoff)) return null;

        String description = "";
        if (entry.getDescription() != null) {
            description = entry.getDescription().getValue();
          
            description = description.replaceAll("<[^>]+>", "").trim();
            if (description.length() > 500) {
                description = description.substring(0, 500) + "...";
            }
        }

        String imageUrl = "";
        if (entry.getForeignMarkup() != null) {
            for (org.jdom2.Element el : entry.getForeignMarkup()) {
                if ("content".equals(el.getName()) || "thumbnail".equals(el.getName())) {
                    String urlAttr = el.getAttributeValue("url");
                    if (urlAttr != null && !urlAttr.isBlank()) {
                        imageUrl = urlAttr;
                        break;
                    }
                }
            }
        }

        return Article.builder()
                .title(title)
                .url(url)
                .source("rss")
                .sourceDomain(sourceName)
                .description(description)
                .imageUrl(imageUrl)
                .publishedAt(publishedAt)
                .build();
    }

    private String extractDomain(String url) {
        try {
            java.net.URI uri = new java.net.URI(url);
            String host = uri.getHost();
            return host != null ? host.replaceFirst("^www\\.", "") : url;
        } catch (Exception e) {
            return url;
        }
    }
}
