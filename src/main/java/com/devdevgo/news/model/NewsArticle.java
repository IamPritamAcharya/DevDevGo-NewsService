package com.devdevgo.news.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsArticle {

    private String id;
    private String title;
    private String summary;
    private String source;
    private String sourceDomain;
    private String url;
    private String imageUrl;
    private Instant publishedAt;
    private Instant createdAt;
    private double score;
    private List<String> tags;
}
