package com.devdevgo.news.summarizer;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ContentCleaner {

    @Value("${news.pipeline.summarizer.content-max-words:800}")
    private int maxWords;

    public String clean(String rawContent) {
        if (rawContent == null || rawContent.isBlank())
            return "";

        String text;
        try {

            Document doc = Jsoup.parse(rawContent);

            doc.select("script, style, nav, footer, header, aside, .ad, .advertisement, .social-share").remove();
            text = doc.text();
        } catch (Exception e) {

            text = rawContent.replaceAll("<[^>]+>", " ");
        }

        text = text.replaceAll("\\s+", " ").trim();

        String[] words = text.split("\\s+");
        if (words.length > maxWords) {
            text = Arrays.stream(words)
                    .limit(maxWords)
                    .collect(Collectors.joining(" "));
        }

        return text;
    }

    public String cleanDescription(String description) {
        if (description == null || description.isBlank())
            return "";
        return Jsoup.clean(description, Safelist.none())
                .replaceAll("\\s+", " ")
                .trim();
    }
}
