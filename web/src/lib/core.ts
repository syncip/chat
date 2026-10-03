import init, * as wasm from '../wasm/chat_core.js';

export type Core = typeof wasm;
export type { Client, Vault } from '../wasm/chat_core.js';

let ready: Promise<Core> | null = null;

/** Lädt den Krypto-Kern (Rust/WASM) genau einmal. */
export function loadCore(): Promise<Core> {
  ready ??= init().then(() => wasm);
  return ready;
}
