FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY crdt-core/pom.xml crdt-core/
COPY app/pom.xml app/

RUN ./mvnw -B -q dependency:go-offline -pl app -am || true

COPY crdt-core/ crdt-core/
COPY app/ app/

RUN ./mvnw -B -q -pl app -am -DskipTests package

WORKDIR /build
RUN cp /src/app/target/*.jar app.jar && java -Djarmode=tools -jar app.jar extract --layers --launcher

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --uid 10001 --shell /usr/sbin/nologin collabedit

ARG EXTRACTED=/build/app
COPY --from=build --chown=10001:0 ${EXTRACTED}/dependencies/ ./
COPY --from=build --chown=10001:0 ${EXTRACTED}/spring-boot-loader/ ./
COPY --from=build --chown=10001:0 ${EXTRACTED}/snapshot-dependencies/ ./
COPY --from=build --chown=10001:0 ${EXTRACTED}/application/ ./

USER 10001
EXPOSE 8090

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD curl --fail --silent http://localhost:8090/actuator/health || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
