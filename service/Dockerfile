# The service as a plain JVM jar, which is what step 13 deploys.
#
# Spec §9.6 puts the native image last on purpose: get the service working as a jar,
# deploy it, verify it over HTTP, and only then fight GraalVM's reflection config. A
# broken native build on top of an unverified request path is two problems at once, and
# this file is the baseline the native one will be measured against — the README wants a
# cold-start number from before and after.
#
# Build context is the repo root, because the Gradle wrapper and settings.gradle.kts live
# there: docker build -f service/Dockerfile .

FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /src

# The wrapper and every build file land first — sampler's too, because
# settings.gradle.kts includes that project and Gradle configures it even for a
# :service task. They change far less often than the sources, so the dependency
# download they trigger survives an ordinary code change.
COPY gradlew settings.gradle.kts ./
COPY gradle gradle
COPY sampler/build.gradle.kts sampler/build.gradle.kts
COPY service/build.gradle.kts service/build.gradle.kts
RUN ./gradlew :service:dependencies --configuration runtimeClasspath --no-daemon

COPY service/src service/src
RUN ./gradlew :service:bootJar --no-daemon

FROM eclipse-temurin:21-jre-jammy

# Nothing in here needs root. Cloud Run does not require a non-root user, but a
# container that never needs to write outside /tmp may as well not be able to.
RUN useradd --system --create-home --uid 10001 forecast
USER forecast
WORKDIR /app
COPY --from=builder /src/service/build/libs/service.jar app.jar

# MaxRAMPercentage rather than a fixed -Xmx, so --memory in the deploy flags stays the
# single place the limit is decided. SerialGC because the container has one CPU: a
# parallel collector on one core spends its time coordinating with itself.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC"

# Cloud Run injects PORT and application.yml already reads it, so no port is hardcoded
# here. No secrets either: the key, the origin secret, the IP hash key and the database
# password all arrive as environment variables from Secret Manager at deploy time.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
