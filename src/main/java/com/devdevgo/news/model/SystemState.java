package com.devdevgo.news.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemState {

    private Instant lastFetchedAt;
    private String lastRunStatus;   
    private int lastArticleCount;
}
