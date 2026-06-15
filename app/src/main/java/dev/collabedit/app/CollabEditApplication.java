package dev.collabedit.app;

import dev.collabedit.app.config.CollabEditProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * A backend instance.
 *
 * <p>Per ADR-0004 this is <b>an ordinary CRDT replica</b>, a peer of the other instances and of the
 * browsers. There is no special role here: no instance owns a document, there is no leader
 * election and no distributed lock. Running three copies of this process behind a plain
 * round-robin load balancer is a valid configuration.
 */
@SpringBootApplication
@EnableConfigurationProperties(CollabEditProperties.class)
@EnableScheduling
public class CollabEditApplication {

    public static void main(String[] args) {
        SpringApplication.run(CollabEditApplication.class, args);
    }
}
