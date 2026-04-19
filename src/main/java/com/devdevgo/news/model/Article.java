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
public class Article {

    private String title;
    private String url;
    private String normalizedUrl;
    private String source;          
    private String sourceDomain;    
    private String description;      
    private String imageUrl;
    private Instant publishedAt;
    private int upvotes;
    private int comments;

    private double recencyScore;
    private double sourceScore;
    private double engagementScore;
    private double titleQualityScore;
    private double keywordBoost;
    private double finalScore;

    private String summary;

    private java.util.List<String> tags;
}
