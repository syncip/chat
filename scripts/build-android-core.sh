#!/usr/bin/env bash
# Baut den Rust-Kern für Android (arm64-v8a, armeabi-v7a, x86_64) nach android/app/src/main/jniLibs.
# Voraussetzungen: Android NDK (ANDROID_NDK_HOME), `cargo install cargo-ndk`,
#   rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
set -euo pipefail
cd "$(dirname "$0")/../core"
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o ../android/app/src/main/jniLibs build --release --features uniffi
