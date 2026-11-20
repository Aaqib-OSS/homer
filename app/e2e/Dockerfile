# Test-runner image for the black-box E2E suite. Build context = repo root
# (same as app/Dockerfile). The Gradle distribution and the :e2e dependency
# graph are warmed into their own layer — before the test sources are
# copied — so editing a test only recompiles, it never re-downloads.
FROM eclipse-temurin:25-jdk-alpine-3.23@sha256:9c835a4ba215132c44248947376e270323ccca5a770248dd5cdf6c1711ebfa1f

RUN apk add --no-cache bash

WORKDIR /e2e

COPY app/gradle ./gradle
COPY app/gradlew app/gradle.properties app/settings.gradle.kts ./
COPY app/buildSrc ./buildSrc
COPY app/e2e/build.gradle.kts ./e2e/build.gradle.kts

# Empty dirs stand in for the other projects and :e2e has no sources yet: this caches deps.
# The $(…) ends in `|| true`, so the export masks no failure.
# hadolint ignore=SC2155
RUN --mount=type=secret,id=artifactory_url \
    --mount=type=secret,id=artifactory_user \
    --mount=type=secret,id=artifactory_token \
    mkdir -p app agentdebug homer-runner architecture && \
    export ARTIFACTORY_URL="$(cat /run/secrets/artifactory_url 2>/dev/null || true)" && \
    export ARTIFACTORY_USER="$(cat /run/secrets/artifactory_user 2>/dev/null || true)" && \
    export ARTIFACTORY_TOKEN="$(cat /run/secrets/artifactory_token 2>/dev/null || true)" && \
    ./gradlew :e2e:dependencies :e2e:testClasses --no-daemon

COPY app/e2e ./e2e

# The $(…) ends in `|| true`, so the export masks no failure.
# hadolint ignore=SC2155
RUN --mount=type=secret,id=artifactory_url \
    --mount=type=secret,id=artifactory_user \
    --mount=type=secret,id=artifactory_token \
    export ARTIFACTORY_URL="$(cat /run/secrets/artifactory_url 2>/dev/null || true)" && \
    export ARTIFACTORY_USER="$(cat /run/secrets/artifactory_user 2>/dev/null || true)" && \
    export ARTIFACTORY_TOKEN="$(cat /run/secrets/artifactory_token 2>/dev/null || true)" && \
    ./gradlew :e2e:testClasses --no-daemon

ENTRYPOINT ["./gradlew", ":e2e:test", "--no-daemon"]
