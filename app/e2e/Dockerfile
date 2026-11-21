# Test-runner image for the black-box E2E suite; the build context is the repo root, as for app/Dockerfile.
# Gradle and the :e2e dependencies warm in a layer before the test sources, so editing a test never re-downloads.
FROM eclipse-temurin:25-jdk-alpine-3.23@sha256:9c835a4ba215132c44248947376e270323ccca5a770248dd5cdf6c1711ebfa1f

RUN apk add --no-cache bash

WORKDIR /e2e

COPY app/gradle ./gradle
COPY app/gradlew app/gradle.properties app/settings.gradle.kts ./
COPY app/buildSrc ./buildSrc
COPY app/e2e/build.gradle.kts ./e2e/build.gradle.kts

# Empty dirs stand in for the other projects and :e2e has no sources yet: this caches deps.
RUN mkdir -p app agentdebug homer-runner architecture && \
    ./gradlew :e2e:dependencies :e2e:testClasses --no-daemon

COPY app/e2e ./e2e

RUN ./gradlew :e2e:testClasses --no-daemon

# Without a server to test, the suite fails here rather than skipping every test and passing.
ENV E2E_REQUIRED=true

ENTRYPOINT ["./gradlew", ":e2e:test", "--no-daemon"]
