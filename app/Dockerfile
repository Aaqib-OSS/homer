# Multi-stage Dockerfile for Homer
# Stage 1: Build the fat jar
FROM eclipse-temurin:25-jdk-alpine-3.23@sha256:9c835a4ba215132c44248947376e270323ccca5a770248dd5cdf6c1711ebfa1f AS builder

WORKDIR /build

# Install bash for Gradle wrapper, zip to trim the fat jar
RUN apk add --no-cache bash zip

# The build context is the repo root, so COPY sources carry the app/ prefix; the in-image layout is unchanged.
COPY app/gradle ./gradle
COPY app/gradlew ./
COPY app/gradle.properties ./
COPY app/settings.gradle.kts ./
COPY app/buildSrc ./buildSrc

# Copy source code (must copy before running gradle)
COPY app/app ./app
COPY app/agentdebug ./agentdebug
COPY app/homer-runner ./homer-runner
COPY app/schemas ./schemas

# settings.gradle.kts includes :e2e and :architecture, never built here; empty dirs satisfy Gradle.
RUN mkdir -p e2e architecture

# Caches the Gradle distribution, buildSrc and plugins in their own layer.
RUN ./gradlew dependencies --no-daemon

# Build fat jar. The release tag is stamped into the runner jar (its name and the version it
# registers with); declared after the dependency layer so a version change does not refetch them.
ARG HOMER_VERSION=dev
RUN ./gradlew shadowJar --no-daemon -PhomerVersion="$HOMER_VERSION"

# Alpine (musl) loads only sqlite-jdbc's Linux-Musl native library, so the image's jar drops the
# other platforms' copies; a source build keeps them (ADR-0015).
RUN zip -q -d app/build/libs/homer-*-all.jar 'org/sqlite/native/*' -x 'org/sqlite/native/Linux-Musl/*'

# Stage 2: the slim server image (ADR-0094): Homer, a JRE, bash, su-exec, curl and the LangGraph
# runtime, with no agent toolchain.
FROM eclipse-temurin:25-jre-alpine-3.23@sha256:e3ec242a23cf736c024475da9cd2a26f1f9d896840dd797da1bb95d30ed3f659 AS slim

ARG HOMER_VERSION=dev
ENV HOMER_VERSION=$HOMER_VERSION

RUN apk add --no-cache \
    bash \
    curl \
    su-exec \
    python3 \
    py3-pip

# The !<langgraph> runtime: the hash-pinned requirements.lock, never a resolve, which backtracks on
# musl (ADR-0114). --break-system-packages: PEP 668 refuses a system-wide install on Alpine otherwise.
COPY clients/python /tmp/homer_langgraph_src
RUN pip install --no-cache-dir --break-system-packages --require-hashes --no-deps -r /tmp/homer_langgraph_src/requirements.lock && \
    pip install --no-cache-dir --break-system-packages --no-deps --no-build-isolation /tmp/homer_langgraph_src && \
    rm -rf /tmp/homer_langgraph_src

# Create homer group and user (GID/UID 1000, home at /home/homer)
RUN addgroup -g 1000 homer && \
    adduser -u 1000 -G homer -h /home/homer -s /bin/bash -D homer

# /app and /app/config stay root-owned: a homer-writable entrypoint would give an agent root on the next
# restart (CWE-732/269). homer gets only workspace, agents and its home.
RUN mkdir -p /app/config /app/workspace /app/agents && \
    chown -R homer:homer /app/workspace /app/agents /home/homer

WORKDIR /app

# Root-owned jar: homer must not be able to swap in one the root restart would execute (CWE-732/269).
COPY --from=builder /build/app/build/libs/homer-*-all.jar /app/homer.jar

# Debug build: conditionally copy agentdebug JAR
ARG HOMER_DEBUG=0
ENV HOMER_DEBUG=$HOMER_DEBUG
RUN --mount=type=bind,from=builder,source=/build/agentdebug/build/libs,target=/tmp/agentdebug-libs \
    if [ "$HOMER_DEBUG" = "1" ]; then \
      cp /tmp/agentdebug-libs/agentdebug-*-all.jar /app/agentdebug.jar; \
    fi

# agentdebug wrapper, always present; the single quotes leave ${CONFIG_PATH…} and $@ to it.
# hadolint ignore=SC2016
RUN printf '#!/bin/sh\nset -e\nif [ ! -f /app/agentdebug.jar ]; then\n  echo "Error: agentdebug not available. Rebuild the container with HOMER_DEBUG=1." >&2\n  exit 1\nfi\nexec su-exec homer java -XX:-UseBASE64Intrinsics --enable-native-access=ALL-UNNAMED \\\n  -jar /app/agentdebug.jar \\\n  --config "${CONFIG_PATH:-config/config.json}" "$@"\n' > /usr/local/bin/agentdebug && \
    chmod +x /usr/local/bin/agentdebug

# Root-owned entrypoint: it runs as root, so homer must not be able to replace it (CWE-732/269).
COPY --chown=root:root --chmod=755 app/entrypoint.sh /app/entrypoint.sh

EXPOSE 8080

ENV JAVA_OPTS="-Xmx512m -Xms256m"
ENV CONFIG_PATH="config/config.json"
# Place api.log beside app.log in the workspace volume (read by logback.xml).
ENV HOMER_LOG_DIR="/app/workspace"

ENTRYPOINT ["/app/entrypoint.sh"]

# Stage 3: the full server image (homer-app): the slim image plus the agent toolchain
# that local !<claude>, !<codex> and shell-command runs use.
FROM slim AS runner

RUN apk add --no-cache \
    git \
    git-lfs \
    nodejs \
    npm \
    openssh-client \
    glab \
    jq \
    unzip

# The agent CLIs are pinned (CWE-1357): an unpinned install ships every later release of a binary that runs
# prompts with the workspace's secrets. CLAUDE_CODE_VERSION must match server.requireClaudeCodeVersion.
ARG CLAUDE_CODE_VERSION=2.1.318
ARG CODEX_VERSION=0.166.0
ARG OPENCODE_VERSION=1.19.5
# The npm cache is cleaned in the same layer: nothing reads it at run time.
RUN npm install -g @anthropic-ai/claude-code@${CLAUDE_CODE_VERSION} @openai/codex@${CODEX_VERSION} opencode-ai@${OPENCODE_VERSION} && \
    npm cache clean --force

# glab checks its release host first, and a stalled route there held this step to the CI timeout: the
# check is off, for agents too, and the step bounded.
USER homer
RUN GLAB_CHECK_UPDATE=false timeout 60 glab config set check_update false --global \
 && GLAB_CHECK_UPDATE=false timeout 60 glab config set git_protocol https --global
RUN git lfs install

# entrypoint.sh starts as root to fix volume permissions, then drops to homer via su-exec.
# hadolint ignore=DL3002
USER root

# Stage 4: the external runner (ADR-0066), deliberately small: derive from it to add toolchains.
# util-linux provides setsid, so killed commands take their process group with them (ADR-0041).
FROM eclipse-temurin:25-jre-alpine-3.23@sha256:e3ec242a23cf736c024475da9cd2a26f1f9d896840dd797da1bb95d30ed3f659 AS homer-runner

ARG HOMER_VERSION=dev
ENV HOMER_VERSION=$HOMER_VERSION

RUN apk add --no-cache bash curl git tar gzip util-linux

RUN addgroup -g 1000 homer && \
    adduser -u 1000 -G homer -h /home/homer -s /bin/bash -D homer && \
    mkdir -p /app /var/homer-runner && \
    chown homer:homer /var/homer-runner

# Root-owned jar: the runs execute as homer and must not be able to swap it.
COPY --from=builder /build/homer-runner/build/libs/homer-runner-*-all.jar /app/homer-runner.jar

USER homer
WORKDIR /var/homer-runner

ENV HOMER_RUNNER_ROOT=/var/homer-runner
ENV JAVA_OPTS="-Xmx256m"

# Options: HOMER_RUNNER_* env vars or the command line; -XX:-UseBASE64Intrinsics (W-020) leads JAVA_OPTS.
#   docker run -e HOMER_RUNNER_URL=... -e HOMER_RUNNER_TOKEN=... -e HOMER_RUNNER_TAGS=linux homer-runner
ENTRYPOINT ["/bin/sh", "-c", "exec java -XX:-UseBASE64Intrinsics $JAVA_OPTS --enable-native-access=ALL-UNNAMED -jar /app/homer-runner.jar \"$@\"", "--"]
