package com.devdevgo.news;

import com.devdevgo.news.scheduler.NewsPipelineScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@SpringBootApplication
@EnableScheduling
@RequiredArgsConstructor
public class NewsServiceApplication implements CommandLineRunner {
    private final NewsPipelineScheduler scheduler;

    public static void main(String[] args) {
        SpringApplication.run(NewsServiceApplication.class, args);
    }

    @Override
    public void run(String... args) {
        log.info("=== News Service Starting - Running startup recovery check ===");
        scheduler.runStartupRecovery();
    }
}
