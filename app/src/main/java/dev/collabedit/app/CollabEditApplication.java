package dev.collabedit.app;

import dev.collabedit.app.config.CollabEditProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Instancja backendu.
 *
 * <p>Zgodnie z ADR-0004 jest to <b>zwykła replika CRDT</b>, równorzędna wobec pozostałych
 * instancji i wobec przeglądarek. Nie ma tu roli szczególnej: żadna instancja nie jest
 * właścicielem dokumentu, nie ma wyboru lidera i nie ma rozproszonej blokady. Uruchomienie
 * trzech kopii tego procesu za zwykłym load balancerem round-robin jest poprawną konfiguracją.
 */
@SpringBootApplication
@EnableConfigurationProperties(CollabEditProperties.class)
@EnableScheduling
public class CollabEditApplication {

    public static void main(String[] args) {
        SpringApplication.run(CollabEditApplication.class, args);
    }
}
