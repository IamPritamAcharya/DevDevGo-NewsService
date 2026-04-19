package com.devdevgo.news.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

@Slf4j
@Configuration
public class FirebaseConfig {

    @Value("${news.firebase.credentials-path}")
    private String credentialsPath;

    @Value("${news.firebase.database-url}")
    private String databaseUrl;

    @Bean
    public FirebaseApp firebaseApp() throws IOException {
        if (!FirebaseApp.getApps().isEmpty()) {
            return FirebaseApp.getInstance();
        }

        InputStream serviceAccount;
        // Try classpath first, then file system
        InputStream classPathStream = getClass().getClassLoader().getResourceAsStream(credentialsPath);
        if (classPathStream != null) {
            serviceAccount = classPathStream;
            log.info("Firebase: loaded credentials from classpath: {}", credentialsPath);
        } else {
            serviceAccount = new FileInputStream(credentialsPath);
            log.info("Firebase: loaded credentials from filesystem: {}", credentialsPath);
        }

        FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                .setDatabaseUrl(databaseUrl)
                .build();

        FirebaseApp app = FirebaseApp.initializeApp(options);
        log.info("Firebase initialized successfully");
        return app;
    }
}
