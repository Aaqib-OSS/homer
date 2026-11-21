# Dependencies stage
FROM node:24-alpine@sha256:116b800c3754b440ee0aa3ed2f20f5bf3d99a554bfd9adeea09ea8017f6e85f5 AS deps
RUN apk add --no-cache libc6-compat
WORKDIR /app

# Install dependencies based on the preferred package manager
COPY package.json package-lock.json* ./
RUN npm ci

# Build stage
FROM node:24-alpine@sha256:116b800c3754b440ee0aa3ed2f20f5bf3d99a554bfd9adeea09ea8017f6e85f5 AS builder
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .

ARG CONSOLE_VERSION=dev
ENV NEXT_PUBLIC_CONSOLE_VERSION=$CONSOLE_VERSION

# Build the application
RUN npm run build

# Keeps sharp's LGPL-3.0 binaries out of every layer (ADR-0056); the Release
# workflow's LGPL gate refuses the push if any remain.
RUN rm -rf .next/standalone/node_modules/@img .next/standalone/node_modules/sharp

# The release image's content with the base image's npm, which docker-compose.dev.yml runs.
FROM node:24-alpine@sha256:116b800c3754b440ee0aa3ed2f20f5bf3d99a554bfd9adeea09ea8017f6e85f5 AS dev
WORKDIR /app

ENV NODE_ENV=production

# zlib and OpenSSL from the repository, newer than the base digest's builds (W-023).
RUN apk add --no-cache --upgrade zlib libcrypto3 libssl3

# Create non-root user
RUN addgroup --system --gid 1001 nodejs
RUN adduser --system --uid 1001 nextjs

# The standalone output with its node_modules; --chown inline, since a separate
# `RUN chown -R /app` would duplicate every app file into a new layer (~86 MB).
COPY --from=builder --chown=nextjs:nodejs /app/.next/standalone ./
# Copy static files
COPY --from=builder --chown=nextjs:nodejs /app/.next/static ./.next/static
# `output: standalone` does not include public/, and it has to be here for
# runtime plugins to load: CONSOLE_PLUGINS paths are served from public/plugins.
COPY --from=builder --chown=nextjs:nodejs /app/public ./public

USER nextjs

EXPOSE 3000

ENV PORT=3000
ENV HOSTNAME="0.0.0.0"

CMD ["node", "server.js"]

# Production stage: nothing in it runs npm or npx, so the release image drops them.
FROM dev AS runner
USER root
RUN rm -rf /usr/local/lib/node_modules/npm /usr/local/bin/npm /usr/local/bin/npx
USER nextjs
