plugins {
    java
    alias(libs.plugins.jmh)
}

// ŚWIADOME OGRANICZENIE: ten moduł nie ma i nie będzie miał zależności od Springa
// ani od czegokolwiek z warstwy aplikacyjnej (ADR-0001). Jedyna zależność produkcyjna
// to Jackson — i tylko do serializacji operacji na potrzeby transportu/persystencji.
// Jeśli kiedyś pojawi się tu `org.springframework`, to znaczy, że granica przeciekła.
dependencies {
    implementation(libs.jackson.databind)

    testImplementation(libs.jqwik)
    testImplementation(libs.assertj)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    // jqwik i JUnit Jupiter obok siebie: property testy i zwykłe testy jednostkowe.
    useJUnitPlatform {
        includeEngines("jqwik", "junit-jupiter")
    }

    // Determinizm w CI, losowość w nocnym buildzie.
    //   - domyślnie: stały seed → ten sam wynik przy każdym uruchomieniu, kontrprzykład
    //     da się odtworzyć jednym poleceniem,
    //   - `-Pjqwik.thorough`: losowy seed + 10× więcej prób → nocny build faktycznie
    //     szuka nowych kontrprzykładów zamiast powtarzać te same 1000 przypadków.
    // Kontrprzykłady znalezione nocą trafiają do repo jako zwykłe testy regresyjne.
    val thorough = providers.gradleProperty("jqwik.thorough").isPresent
    systemProperty("jqwik.tries.default", if (thorough) "10000" else "1000")
    systemProperty("jqwik.seeds.whenfixed", if (thorough) "ALLOW" else "ALLOW")
    if (!thorough) {
        systemProperty("jqwik.database", "${layout.buildDirectory.get()}/jqwik-database")
    }
    systemProperty("jqwik.failures.after.default", "PREVIOUS_SEED")
    systemProperty("jqwik.reporting.usejunitplatform", "true")
}

jmh {
    // Benchmarki mają dać liczby do README (CLAUDE.md §5), więc parametry są jawne,
    // a nie domyślne. 3 iteracje rozgrzewki, bo JIT na strukturach z dużą liczbą
    // obiektów potrzebuje chwili, zanim wynik przestaje dryfować.
    warmupIterations = 3
    iterations = 5
    fork = 1
    timeUnit = "us"
    resultFormat = "JSON"
}

// UWAGA: wtyczka JMH nie jest zgodna z configuration cache (próbuje serializować obiekt
// Project). Zamiast wyłączać cache dla całego projektu — gdzie daje realny zysk przy
// częstym uruchamianiu property testów — wyłączam go punktowo dla zadań JMH.
// Uruchomienie: ./gradlew :crdt-core:jmh --no-configuration-cache
tasks.matching { it.name.startsWith("jmh") }.configureEach {
    notCompatibleWithConfigurationCache("wtyczka me.champeau.jmh nie wspiera configuration cache")
}
