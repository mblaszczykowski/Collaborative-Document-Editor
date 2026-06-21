# Backend — a multi-stage image.
#
# The rule for this file is the same as for docker-compose.yml: no value without a comment saying
# why that one.

# --- Stage 1: build ---------------------------------------------------------
#
# JDK 21, because that is what maven.compiler.release targets (pom.xml). The build image is
# discarded afterwards, so its size does not matter — all that matters is that it is the same
# version we build with locally and in CI.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# The descriptors are copied BEFORE the code, so that dependency resolution gets its own layer.
# Without this, any change to a source file invalidates the cache and forces the whole Spring
# dependency tree to be downloaded again — several minutes on every build.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY crdt-core/pom.xml crdt-core/
COPY app/pom.xml app/

# go-offline trips over the inter-module dependency (crdt-core is not built yet), hence
# `|| true`: the goal is to fill the layer with dependencies, not to be complete. The proper build
# below pulls in whatever is missing.
#
# No `--mount=type=cache`, even though it would speed up rebuilds: it requires BuildKit, and that
# is not enabled in every Docker setup (colima with the default builder, for instance). An image
# that builds everywhere is worth more than one that builds faster for half the people. The layer
# split below gives most of the benefit anyway.
RUN ./mvnw -B -q dependency:go-offline -pl app -am || true

COPY crdt-core/ crdt-core/
COPY app/ app/

# The tests are NOT run while building the image. They need Docker (Testcontainers), that is,
# Docker in Docker — and that is what CI is for, where they run on every push anyway. The image is
# meant to be an artifact built from code that has already passed its tests.
RUN ./mvnw -B -q -pl app -am -DskipTests package

# Extracting the layered jar. Spring Boot arranges the contents so that the dependencies (rarely
# changing) and the application classes (changing on every commit) end up in SEPARATE image layers.
# Running the fat jar directly works the same, but a one-line code change would rewrite the whole
# ~60 MB of dependencies.
WORKDIR /build
RUN cp /src/app/target/*.jar app.jar && java -Djarmode=tools -jar app.jar extract --layers --launcher

# --- Stage 2: the runtime image ---------------------------------------------
#
# A JRE rather than a JDK: the compiler, javadoc and tooling are not needed to run and are
# unnecessary attack surface.
FROM eclipse-temurin:21-jre
WORKDIR /app

# curl solely for the HEALTHCHECK below. The JRE image does not include it, and a healthcheck that
# only checked an open port would answer "is the process alive" when what we care about is "is this
# instance ready to take traffic".
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# A non-root user. A container that does not need root should not have it — the application writes
# nothing outside /tmp and binds no privileged ports.
RUN useradd --system --uid 10001 --shell /usr/sbin/nologin collabedit

# The layers are ordered from least to most frequently changing, so that an ordinary commit
# invalidates only the last one.
ARG EXTRACTED=/build/app
COPY --from=build --chown=10001:0 ${EXTRACTED}/dependencies/ ./
COPY --from=build --chown=10001:0 ${EXTRACTED}/spring-boot-loader/ ./
COPY --from=build --chown=10001:0 ${EXTRACTED}/snapshot-dependencies/ ./
COPY --from=build --chown=10001:0 ${EXTRACTED}/application/ ./

USER 10001
EXPOSE 8080

# MaxRAMPercentage rather than -Xmx: the memory limit is set by the orchestrator and the JVM is
# meant to adapt to it, rather than carrying a hardcoded value that, when the container limit
# changes, either wastes memory or causes an OOM kill.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# The healthcheck asks the actuator, not just the TCP port. An open port means "the process is
# alive"; /actuator/health means "the database answers and the Flyway migrations went through" —
# and that is the difference between an instance ready to take traffic and one that will refuse
# every request.
#
# A start-period of 40 s, because startup includes the Flyway migrations and the first connection
# to Postgres; a shorter value would mean restarting the container mid-startup.
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD curl --fail --silent http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
