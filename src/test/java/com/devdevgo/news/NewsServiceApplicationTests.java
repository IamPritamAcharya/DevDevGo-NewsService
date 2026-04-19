package com.devdevgo.news;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "news.api.gnews-key=test",
        "news.api.reddit-client-id=test",
        "news.api.reddit-client-secret=test",
        "news.api.gemini-key-primary=test",
        "news.api.gemini-key-fallback=test",
        "news.firebase.credentials-path=test-firebase.json",
        "news.firebase.database-url=https://test.firebaseio.com",
        "spring.autoconfigure.exclude=com.devdevgo.news.config.FirebaseConfig"
})
class NewsServiceApplicationTests {

    @Test
    void contextLoads() {
        // Context load test — Firebase is excluded for unit tests
    }
}
