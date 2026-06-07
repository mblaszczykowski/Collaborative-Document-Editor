# Backend — obraz wielostopniowy.
#
# Zasada dla tego pliku jest ta sama, co dla docker-compose.yml: żadnej wartości bez
# komentarza, dlaczego akurat taka.

# --- Etap 1: budowanie ------------------------------------------------------
#
# JDK 21, bo na taką wersję celuje maven.compiler.release (pom.xml). Obraz budujący
# jest odrzucany po zakończeniu, więc jego rozmiar nie ma znaczenia — liczy się tylko
# to, żeby był tą samą wersją, na której budujemy lokalnie i w CI.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Deskryptory kopiowane PRZED kodem, żeby pobranie zależności trafiło do własnej warstwy.
# Bez tego każda zmiana w pliku źródłowym unieważnia cache i wymusza ponowne ściągnięcie
# całego drzewa zależności Springa — kilka minut przy każdym buildzie.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY crdt-core/pom.xml crdt-core/
COPY app/pom.xml app/

# go-offline potyka się o zależności międzymodułowe (crdt-core nie jest jeszcze zbudowany),
# stąd `|| true`: celem jest zapełnienie warstwy zależnościami, a nie kompletność.
# Brakujące artefakty dociągnie właściwy build poniżej.
#
# Bez `--mount=type=cache`, choć przyspieszyłby przebudowy: wymaga BuildKita, a ten
# nie jest włączony w każdej konfiguracji Dockera (np. colima z domyślnym builderem).
# Obraz, który buduje się wszędzie, jest wart więcej niż obraz, który buduje się szybciej
# u połowy osób. Podział na warstwy poniżej daje i tak większość zysku.
RUN ./mvnw -B -q dependency:go-offline -pl app -am || true

COPY crdt-core/ crdt-core/
COPY app/ app/

# Testy NIE są uruchamiane przy budowaniu obrazu. Wymagają Dockera (Testcontainers),
# czyli Dockera w Dockerze — a od tego jest CI, gdzie i tak chodzą przy każdym pushu.
# Obraz ma być artefaktem budowanym z kodu, który już przeszedł testy.
RUN ./mvnw -B -q -pl app -am -DskipTests package

# Rozpakowanie warstwowego jara. Spring Boot układa zawartość tak, żeby zależności
# (rzadko zmienne) i klasy aplikacji (zmienne przy każdym commicie) trafiły do OSOBNYCH
# warstw obrazu. Uruchomienie fat-jara wprost działa tak samo, ale każda zmiana jednej
# linijki kodu przepisywałaby całe ~60 MB zależności.
WORKDIR /build
RUN cp /src/app/target/*.jar app.jar && java -Djarmode=tools -jar app.jar extract --layers --launcher

# --- Etap 2: obraz uruchomieniowy -------------------------------------------
#
# JRE zamiast JDK: kompilator, javadoc i narzędzia nie są potrzebne do uruchomienia,
# a stanowią zbędną powierzchnię ataku.
FROM eclipse-temurin:21-jre
WORKDIR /app

# curl wyłącznie dla HEALTHCHECK-u poniżej. Obraz JRE go nie zawiera, a healthcheck
# sprawdzający wyłącznie otwarty port odpowiadałby na pytanie "czy proces żyje",
# podczas gdy interesuje nas "czy instancja jest gotowa przyjąć ruch".
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Użytkownik nie-root. Kontener, który nie potrzebuje roota, nie powinien go mieć —
# aplikacja nie zapisuje niczego poza /tmp i nie wiąże portów uprzywilejowanych.
RUN useradd --system --uid 10001 --shell /usr/sbin/nologin collabedit

# Warstwy w kolejności od najrzadziej do najczęściej zmiennej — tak, żeby przy zwykłym
# commicie unieważniała się wyłącznie ostatnia.
ARG EXTRACTED=/build/app
COPY --from=build --chown=10001:0 ${EXTRACTED}/dependencies/ ./
COPY --from=build --chown=10001:0 ${EXTRACTED}/spring-boot-loader/ ./
COPY --from=build --chown=10001:0 ${EXTRACTED}/snapshot-dependencies/ ./
COPY --from=build --chown=10001:0 ${EXTRACTED}/application/ ./

USER 10001
EXPOSE 8080

# MaxRAMPercentage zamiast -Xmx: limit pamięci ustawia orkiestrator, a JVM ma się do niego
# dostosować, zamiast mieć wpisaną na sztywno wartość, która przy zmianie limitu kontenera
# albo marnuje pamięć, albo powoduje OOM-kill.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# Healthcheck pyta actuator, a nie tylko port TCP. Otwarty port znaczy „proces żyje";
# /actuator/health znaczy „baza odpowiada i migracje Flyway przeszły" — a to jest różnica
# między instancją gotową do przyjęcia ruchu a taką, która odrzuci każde żądanie.
#
# start-period 40 s, bo start obejmuje migracje Flyway i pierwsze połączenie do Postgresa;
# krótsza wartość oznaczałaby restart kontenera w trakcie normalnego startu.
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD curl --fail --silent http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
