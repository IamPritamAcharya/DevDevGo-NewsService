package com.devdevgo.news.scheduler;

import com.devdevgo.news.model.SystemState;
import com.devdevgo.news.storage.FirebaseStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class NewsPipelineScheduler {

    private final NewsPipelineOrchestrator orchestrator;
    private final FirebaseStorageService storageService;

    @Value("${news.pipeline.interval-hours:1}")
    private int intervalHours;

    @Value("${news.pipeline.max-articles:150}")
    private int maxArticles;

    @Value("${news.pipeline.delete-batch:50}")
    private int deleteBatch;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
    public void scheduledRun() {
        log.info("[Scheduler] Hourly trigger fired");
        triggerIfNeeded("scheduler");
    }

    public void runStartupRecovery() {
        log.info("[Startup] Running startup recovery check...");
        triggerIfNeeded("startup-recovery");
    }

    public void triggerExternal() {
        log.info("[External] External trigger received");
        triggerIfNeeded("external");
    }

    private void triggerIfNeeded(String source) {
        if (!running.compareAndSet(false, true)) {
            log.info("[{}] Pipeline already running, skipping duplicate trigger", source);
            return;
        }

        try {
            log.info("[{}] Checking Firebase system state...", source);
            SystemState state = storageService.getSystemState().block();

            if (state == null) {
                log.warn("[{}] Got null system state — running pipeline as fallback", source);
                runPipeline(source);
                return;
            }

            // Always trim old articles on every check, regardless of whether pipeline runs
            trimOldArticles(source);

            Instant lastRun = state.getLastFetchedAt();
            Duration elapsed = Duration.between(lastRun, Instant.now());
            long hoursElapsed = elapsed.toHours();
            long minutesElapsed = elapsed.toMinutesPart();

            log.info("[{}] Last run: {} | Elapsed: {}h {}m | Required: {}h",
                    source, lastRun, hoursElapsed, minutesElapsed, intervalHours);

            if (hoursElapsed >= intervalHours) {
                log.info("[{}] ✅ Interval elapsed — starting pipeline", source);
                runPipeline(source);
            } else {
                long minutesRemaining = (intervalHours * 60L) - elapsed.toMinutes();
                log.info("[{}] ⏭ Skipping — next run in ~{} minutes", source, minutesRemaining);
            }

        } catch (Exception e) {
            log.error("[{}] State check failed: {} — running pipeline as fallback", source, e.getMessage(), e);
            runPipeline(source);
        } finally {
            running.set(false);
        }
    }

    private void trimOldArticles(String source) {
        try {
            log.info("[{}] Checking article count for trim...", source);
            storageService.trimArticlesIfNeeded(maxArticles, deleteBatch).block();
        } catch (Exception e) {
            // Non-fatal — log and continue. Don't let trim failure block the pipeline.
            log.warn("[{}] Article trim failed (non-fatal): {}", source, e.getMessage());
        }
    }

    private void runPipeline(String source) {
        log.info("[{}] Executing full pipeline...", source);
        try {
            Integer result = orchestrator.execute().block();
            if (result != null && result > 0) {
                log.info("[{}] ✅ Pipeline complete — {} articles stored", source, result);
            } else {
                log.warn("[{}] ⚠ Pipeline finished with result: {}", source, result);
            }
        } catch (Exception e) {
            log.error("[{}] Pipeline execution error: {}", source, e.getMessage(), e);
        }
    }
}