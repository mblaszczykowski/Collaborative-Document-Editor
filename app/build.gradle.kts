plugins {
    java
    alias(libs.plugins.springBoot)
    alias(libs.plugins.springDepMgmt)
}

dependencies {
    implementation(project(":crdt-core"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // Flyway zamiast ddl-auto: migracje są częścią historii repo (ADR-0003 opiera się
    // na konkretnym kształcie tabel, więc zmiana schematu powinna być commitem, nie
    // efektem ubocznym startu aplikacji).
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Metryki → Prometheus (CP7)
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(libs.assertj)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgres)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    // Testy integracyjne (Testcontainers) wymagają działającego Dockera. Bez niego
    // build ma jasno powiedzieć dlaczego, zamiast wywalić się stacktrace'em z docker-java.
    doFirst {
        val dockerAvailable = try {
            ProcessBuilder("docker", "info")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start().waitFor() == 0
        } catch (_: Exception) {
            false
        }
        if (!dockerAvailable) {
            logger.warn(
                "\n>>> Docker nie jest dostępny — testy integracyjne z Testcontainers zostaną pominięte.\n" +
                    ">>> Zainstaluj Docker Desktop albo `brew install colima docker && colima start`.\n"
            )
            systemProperty("skip.integration.tests", "true")
        }
    }
}
