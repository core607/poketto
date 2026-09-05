# syntax=docker/dockerfile:1
FROM node:24.19.0-bookworm-slim@sha256:a9f5f7c91a432850b2a8a7797adf5eadb6c733ceed61167806cee7ea7fbc29df AS build
WORKDIR /src
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm install --global npm@12.0.2
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --ignore-scripts
COPY frontend/ ./
RUN npm run build

FROM node:24.19.0-bookworm-slim@sha256:a9f5f7c91a432850b2a8a7797adf5eadb6c733ceed61167806cee7ea7fbc29df
ARG POKETTO_REVISION
LABEL org.opencontainers.image.revision="${POKETTO_REVISION}" \
      org.opencontainers.image.source="https://github.com/core607/poketto"
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 HOSTNAME=0.0.0.0 PORT=3000
COPY --from=build --chown=node:node /src/.next/standalone/ ./
COPY --from=build --chown=node:node /src/.next/static/ ./.next/static/
USER node
EXPOSE 3000
CMD ["node", "server.js"]
