// Import jawny, bo w skrypcie Gradle'a `java` odnosi się do rozszerzenia JavaPluginExtension,
// więc `java.time.Duration` się nie rozwiązuje.
import java.time.Duration

plugins {
    java
}

allprojects {
    group = "dev.collabedit"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        // Toolchain, nie `sourceCompatibility` — build ma dawać ten sam wynik niezależnie
        // od tego, jakie JDK ma programista w PATH. Na tej maszynie domyślne java to 25-ea;
        // bez toolchainu build byłby nieodtwarzalny między moją maszyną a CI.
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(
            listOf(
                "-Xlint:all",
                // Ostrzeżenia traktowane jak błędy. W projekcie portfolio "build przechodzi,
                // ale wypluwa 40 warningów" to zły sygnał — łatwiej utrzymać zero od początku
                // niż posprzątać na końcu.
                "-Werror",
                // Wyjątek: `processing` (adnotacje JMH generują szum) i `serial`.
                "-Xlint:-processing,-serial",
            )
        )
        options.release = 21
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStackTraces = true
        }
        // Property testy jqwik potrafią zjeść dużo czasu przy dużej liczbie prób —
        // domyślny brak limitu maskowałby zawieszenie w CI.
        timeout = Duration.ofMinutes(15)
    }
}
