#!/usr/bin/env bash
# Baut den Rust-Kern als WASM und erzeugt die JS-Bindings für den Web-Client.
set -euo pipefail
cd "$(dirname "$0")/../core"
cargo build --release --target wasm32-unknown-unknown --locked
wasm-bindgen --target web --out-dir ../web/src/wasm --typescript \
  target/wasm32-unknown-unknown/release/chat_core.wasm
