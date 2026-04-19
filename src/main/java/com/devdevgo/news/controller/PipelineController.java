package com.devdevgo.news.controller;

import com.devdevgo.news.model.SystemState;
import com.devdevgo.news.scheduler.NewsPipelineScheduler;
import com.devdevgo.news.storage.FirebaseStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

/**
 * REST endpoints for pipeline management.
 *
 * GET /api/health - health check (keeps Render awake if pinged)
 * POST /api/trigger - external trigger (GitHub Actions cron ping)
 * GET /api/status - current pipeline state
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PipelineController {

        private final NewsPipelineScheduler scheduler;
        private final FirebaseStorageService storageService;

        @GetMapping("/health")
        public ResponseEntity<Map<String, Object>> health() {
                return ResponseEntity.ok(Map.of(
                                "status", "UP",
                                "timestamp", Instant.now().toString(),
                                "service", "news-aggregator"));
        }

        @PostMapping("/trigger")
        public ResponseEntity<Map<String, Object>> trigger() {
                log.info("[API] /trigger called");
                Mono.fromRunnable(scheduler::triggerExternal)
                                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                                .subscribe();

                return ResponseEntity.accepted().body(Map.of(
                                "message", "Pipeline trigger accepted",
                                "timestamp", Instant.now().toString()));
        }

        @GetMapping("/trigger")
        public ResponseEntity<Map<String, Object>> triggerGet() {
                return trigger();
        }

        @GetMapping("/status")
        public Mono<ResponseEntity<Map<String, Object>>> status() {
                return storageService.getSystemState()
                                .map(state -> {
                                        Map<String, Object> body = new java.util.LinkedHashMap<>();
                                        body.put("lastFetchedAt",
                                                        state.getLastFetchedAt() != null
                                                                        ? state.getLastFetchedAt().toString()
                                                                        : "never");
                                        body.put("lastRunStatus",
                                                        state.getLastRunStatus() != null ? state.getLastRunStatus()
                                                                        : "unknown");
                                        body.put("lastArticleCount", state.getLastArticleCount());
                                        body.put("timestamp", Instant.now().toString());
                                        return ResponseEntity.<Map<String, Object>>ok(body);
                                })
                                .onErrorResume(e -> {
                                        Map<String, Object> err = new java.util.LinkedHashMap<>();
                                        err.put("error", e.getMessage());
                                        err.put("timestamp", Instant.now().toString());
                                        return Mono.just(ResponseEntity.<Map<String, Object>>internalServerError()
                                                        .body(err));
                                });
        }
}
