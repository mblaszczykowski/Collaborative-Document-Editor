package dev.collabedit.app;

import dev.collabedit.app.config.CollabEditProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(CollabEditProperties.class)
@EnableScheduling
public class CollabEditApplication {
    public static void main(String[] args) {
        SpringApplication.run(CollabEditApplication.class, args);
    }
}
