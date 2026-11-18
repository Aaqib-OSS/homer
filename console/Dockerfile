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

# Image optimization is disabled (next.config.js images.unoptimized), so the
# sharp binaries Next bundles into the standalone output are never loaded at
# runtime. Prune them here, before the runner COPYs the standalone tree, so the
# ~33 MB never enters an image layer.
# Load-bearing beyond size: those binaries are LGPL-3.0-or-later, and keeping
# them out of the image is what keeps Homer from conveying LGPL code. The
# build-homer-console license gate fails the push if this stops working.
RUN rm -rf .next/standalone/node_modules/@img .next/standalone/node_modules/sharp

# Production stage
FROM node:24-alpine@sha256:116b800c3754b440ee0aa3ed2f20f5bf3d99a554bfd9adeea09ea8017f6e85f5 AS runner
WORKDIR /app

ENV NODE_ENV=production

# Create non-root user
RUN addgroup --system --gid 1001 nodejs
RUN adduser --system --uid 1001 nextjs

# Copy the standalone output with all its node_modules. --chown sets ownership
# inline: a separate `RUN chown -R /app` would duplicate every app file into a
# new layer (~86 MB) just to rewrite metadata.
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
