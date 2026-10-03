# syntax=docker/dockerfile:1.7

# ---- 1. Krypto-Kern (Rust → WASM) ----
FROM rust:1 AS core
RUN rustup target add wasm32-unknown-unknown \
 && cargo install wasm-bindgen-cli --version 0.2.129 --locked
WORKDIR /src
COPY core ./core
COPY scripts ./scripts
RUN mkdir -p web/src && cd core \
 && cargo build --release --target wasm32-unknown-unknown --locked \
 && wasm-bindgen --target web --out-dir /src/web/src/wasm --typescript \
      target/wasm32-unknown-unknown/release/chat_core.wasm

# ---- 2. Web-Client ----
FROM node:22-slim AS web
WORKDIR /src/web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web ./
COPY --from=core /src/web/src/wasm ./src/wasm
RUN npm run build

# ---- 3. Server ----
FROM golang:1.26 AS server
WORKDIR /src/server
COPY server/go.mod server/go.sum ./
RUN go mod download
COPY server ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /chatd ./cmd/chatd

# ---- 4. Laufzeit-Image ----
FROM alpine:3.21
RUN apk add --no-cache ca-certificates \
 && adduser -D -u 10001 chat \
 && mkdir /data && chown chat /data
COPY --from=server /chatd /usr/local/bin/chatd
COPY --from=web /src/web/dist /srv/web
ENV CHAT_LISTEN=:8080 CHAT_DATA_DIR=/data CHAT_WEB_DIR=/srv/web
USER chat
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s CMD ["chatd", "healthcheck"]
ENTRYPOINT ["chatd"]
