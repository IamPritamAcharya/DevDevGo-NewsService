package com.devdevgo.news.storage;

import com.devdevgo.news.model.NewsArticle;
import com.devdevgo.news.model.SystemState;
import com.google.cloud.firestore.*;
import com.google.firebase.FirebaseApp;
import com.google.firebase.cloud.FirestoreClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FirebaseStorageService {

    private final FirebaseApp firebaseApp;

    private static final String COLLECTION_NEWS = "news";
    private static final String COLLECTION_SYSTEM_STATE = "system_state";
    private static final String COLLECTION_RECENT_ARTICLES = "recent_articles";
    private static final String STATE_DOC = "news_fetch";


    public Mono<SystemState> getSystemState() {
        return Mono.fromCallable(() -> {
            Firestore db = FirestoreClient.getFirestore(firebaseApp);
            DocumentReference ref = db.collection(COLLECTION_SYSTEM_STATE).document(STATE_DOC);
            DocumentSnapshot doc = ref.get().get();

            if (!doc.exists()) {
                log.info("[State] system_state/news_fetch not found — auto-creating with defaults");
                Map<String, Object> defaults = new HashMap<>();
                defaults.put("lastFetchedAt", 0L);
                defaults.put("lastRunStatus", "never");
                defaults.put("lastArticleCount", 0);
                ref.set(defaults).get();
                log.info("[State] system_state/news_fetch created successfully");

                return SystemState.builder()
                        .lastFetchedAt(Instant.EPOCH)
                        .lastRunStatus("never")
                        .lastArticleCount(0)
                        .build();
            }

            Long epochMilli = doc.getLong("lastFetchedAt");
            String status = doc.getString("lastRunStatus");
            Long count = doc.getLong("lastArticleCount");

            SystemState state = SystemState.builder()
                    .lastFetchedAt(epochMilli != null && epochMilli > 0
                            ? Instant.ofEpochMilli(epochMilli)
                            : Instant.EPOCH)
                    .lastRunStatus(status != null ? status : "never")
                    .lastArticleCount(count != null ? count.intValue() : 0)
                    .build();

            log.info("[State] status={}, lastFetchedAt={}, articleCount={}",
                    state.getLastRunStatus(), state.getLastFetchedAt(), state.getLastArticleCount());
            return state;

        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Void> updateSystemState(SystemState state) {
        return Mono.fromCallable(() -> {
            Firestore db = FirestoreClient.getFirestore(firebaseApp);
            Map<String, Object> data = new HashMap<>();
            data.put("lastFetchedAt", state.getLastFetchedAt().toEpochMilli());
            data.put("lastRunStatus", state.getLastRunStatus());
            data.put("lastArticleCount", state.getLastArticleCount());
            db.collection(COLLECTION_SYSTEM_STATE).document(STATE_DOC).set(data).get();
            log.info("[State] Updated: status={}, articles={}",
                    state.getLastRunStatus(), state.getLastArticleCount());
            return null;
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

   
    public Mono<Integer> saveArticles(List<NewsArticle> articles) {
        return Mono.fromCallable(() -> {
            Firestore db = FirestoreClient.getFirestore(firebaseApp);
            WriteBatch batch = db.batch();
            int count = 0;

            for (NewsArticle article : articles) {
                DocumentReference ref = db.collection(COLLECTION_NEWS).document(article.getId());
                Map<String, Object> data = articleToMap(article);
                batch.set(ref, data);
                count++;

                if (count % 490 == 0) {
                    batch.commit().get();
                    batch = db.batch();
                }
            }
            batch.commit().get();
            log.info("Saved {} articles to Firebase news/ collection", count);
            return count;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> articleToMap(NewsArticle article) {
        Map<String, Object> map = new HashMap<>();
        map.put("title", article.getTitle());
        map.put("summary", article.getSummary());
        map.put("source", article.getSource());
        map.put("sourceDomain", article.getSourceDomain() != null ? article.getSourceDomain() : "");
        map.put("url", article.getUrl());
        map.put("imageUrl", article.getImageUrl() != null ? article.getImageUrl() : "");
        map.put("publishedAt", article.getPublishedAt() != null ? article.getPublishedAt().toEpochMilli() : 0L);
        map.put("createdAt", article.getCreatedAt().toEpochMilli());
        map.put("score", article.getScore());
        map.put("tags", article.getTags() != null ? article.getTags() : List.of());
        return map;
    }


    public Mono<List<Map<String, String>>> getRecentArticleMemory(int memoryHours) {
        return Mono.fromCallable(() -> {
            Firestore db = FirestoreClient.getFirestore(firebaseApp);
            long cutoff = Instant.now().minusSeconds(memoryHours * 3600L).toEpochMilli();

            try {
                QuerySnapshot snapshot = db.collection(COLLECTION_RECENT_ARTICLES)
                        .whereGreaterThan("timestamp", cutoff)
                        .get().get();

                List<Map<String, String>> memory = new ArrayList<>();
                for (DocumentSnapshot doc : snapshot.getDocuments()) {
                    Map<String, String> entry = new HashMap<>();
                    String url = doc.getString("normalizedUrl");
                    String title = doc.getString("normalizedTitle");
                    entry.put("normalizedUrl", url != null ? url : "");
                    entry.put("normalizedTitle", title != null ? title : "");
                    memory.add(entry);
                }
                log.debug("[Dedup] Loaded {} entries from 24h memory", memory.size());
                return memory;
            } catch (Exception e) {
                log.info("[Dedup] recent_articles not found (first run?) — starting with empty memory");
                return new ArrayList<Map<String, String>>();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Void> saveToDeduplicationMemory(List<NewsArticle> articles) {
        return Mono.fromCallable(() -> {
            Firestore db = FirestoreClient.getFirestore(firebaseApp);
            WriteBatch batch = db.batch();
            long now = Instant.now().toEpochMilli();

            for (NewsArticle article : articles) {
                String docId = UUID.randomUUID().toString();
                DocumentReference ref = db.collection(COLLECTION_RECENT_ARTICLES).document(docId);
                Map<String, Object> data = new HashMap<>();
                data.put("normalizedUrl", normalizeUrl(article.getUrl()));
                data.put("normalizedTitle", article.getTitle().toLowerCase().trim());
                data.put("timestamp", now);
                batch.set(ref, data);
            }
            batch.commit().get();
            log.info("[Dedup] Saved {} entries to dedup memory", articles.size());

            cleanOldDeduplicationEntries(db);
            return null;
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private void cleanOldDeduplicationEntries(Firestore db) {
        try {
            long cutoff = Instant.now().minusSeconds(48 * 3600L).toEpochMilli();
            QuerySnapshot old = db.collection(COLLECTION_RECENT_ARTICLES)
                    .whereLessThan("timestamp", cutoff)
                    .get().get();
            if (!old.isEmpty()) {
                WriteBatch batch = db.batch();
                for (DocumentSnapshot doc : old.getDocuments()) {
                    batch.delete(doc.getReference());
                }
                batch.commit().get();
                log.debug("[Dedup] Cleaned {} expired entries", old.size());
            }
        } catch (Exception e) {
            log.warn("[Dedup] Failed to clean old entries: {}", e.getMessage());
        }
    }

    private String normalizeUrl(String url) {
        if (url == null)
            return "";
        return url.toLowerCase().trim()
                .replaceAll("\\?.*$", "")
                .replaceAll("/$", "");
    }
}