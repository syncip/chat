/**
 * Konto-Sync zwischen den Geräten eines Kontos (Web, Android …): Kanäle, Einstellungen, Blocklisten, Kontakte.
 * Ein einziger, mit einem aus dem Konto-Schlüssel abgeleiteten Schlüssel verschlüsselter Blob liegt beim Home-Server
 * (versioniert, Compare-and-Swap); der Server sieht nur Chiffretext. Zusammengeführt wird je Eintrag („last writer wins“, Löschungen als Grabsteine).
 * Format identisch zur Kotlin-Engine (docs/SYNC.md).
 */
import { ApiError } from './api';
import type { Api } from './api';
import type { AppState, ChannelState, Contact } from './types';
import { b64, dec, enc, unb64 } from './util';
import type { Core } from './core';

const KIND_SYNC = 4;
const GID = enc.encode('sync');
const TOMBSTONE_MS = 30 * 24 * 3600 * 1000;

interface Item {
  ts: number;
  del: boolean;
  val?: unknown;
}
interface Doc {
  v: 1;
  items: Record<string, Item>;
}

export interface SyncHost {
  core: Core;
  state(): AppState;
  api(): Api;
  syncKey(): Uint8Array;
  /** Kanal lokal anlegen/entfernen (Netzwerkabgleich übernimmt der Kanal-Manager). */
  applyChannel(id: string, v: { server: string; key: string; title: string; createdAt: number } | null): void;
  notify(): void;
}

const stable = (v: unknown) => JSON.stringify(v);

/** Aktueller lokaler Zustand als Einträge. */
export function collect(s: AppState): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const c of Object.values(s.channels ?? {})) out[`chan:${c.id}`] = { server: c.server, key: c.key, title: c.title, createdAt: c.createdAt };
  out['set:filterMode'] = s.filterMode;
  out['set:serverSideFilter'] = s.serverSideFilter;
  out['set:directSend'] = s.directSend;
  out['set:sendDelivered'] = s.sendDelivered;
  out['set:sendRead'] = s.sendRead;
  out['set:onceDropOwnCopy'] = s.onceDropOwnCopy;
  if (s.me.avatar) out['set:avatar'] = s.me.avatar;
  for (const a of s.blockedUsers) out[`blockU:${a}`] = true;
  for (const a of s.blockedServers) out[`blockS:${a}`] = true;
  for (const a of s.allowUsers) out[`allowU:${a}`] = true;
  for (const a of s.allowServers) out[`allowS:${a}`] = true;
  for (const c of Object.values(s.contacts)) out[`contact:${c.address}`] = { ik: c.ik, verified: c.verified };
  return out;
}

export class AccountSync {
  constructor(private h: SyncHost) {}

  private seal(doc: Doc): string {
    return b64(this.h.core.envelopeSeal(this.h.syncKey(), KIND_SYNC, GID, new Uint8Array(), enc.encode(JSON.stringify(doc))));
  }

  private open(data: string): Doc | null {
    if (!data) return null;
    try {
      const r = this.h.core.envelopeOpen(this.h.syncKey(), unb64(data)) as [number, Uint8Array, Uint8Array, Uint8Array];
      if (r[0] !== KIND_SYNC) return null;
      return JSON.parse(dec.decode(r[3])) as Doc;
    } catch {
      return null;
    }
  }

  /** Signatur des lokalen Zustands (zum schnellen Erkennen von Änderungen). */
  signature(): string {
    return stable(collect(this.h.state()));
  }

  /** Lokale Änderungen erkennen, mit dem Server abgleichen, Fremdänderungen übernehmen. */
  /** Liefert true, wenn Einträge anderer Geräte übernommen wurden (lokaler Stand hat sich dadurch geändert). */
  async run(): Promise<boolean> {
    let appliedAny = false;
    const s = this.h.state();
    const first = !s.sync;
    s.sync ??= { version: 0, base: {} };
    const base = s.sync.base;
    // Erster Abgleich dieses Geräts: eigene Vorgabewerte dürfen Einträge anderer Geräte nicht überschreiben (ts = 0).
    const now = first ? 0 : Date.now();
    const cur = collect(s);
    for (const [k, v] of Object.entries(cur)) {
      const hv = stable(v);
      const b = base[k];
      if (!b || b.h !== hv || b.del) base[k] = { h: hv, ts: now, del: false };
    }
    for (const k of Object.keys(base)) if (!(k in cur) && !base[k].del) base[k] = { h: '', ts: now, del: true };

    for (let attempt = 0; attempt < 5; attempt++) {
      const r = await this.h.api().call<{ version: number; data: string }>('GET', '/v1/sync');
      const doc: Doc = this.open(r.data) ?? { v: 1, items: {} };
      const merged: Record<string, Item> = { ...doc.items };
      let push = false;
      const keys = new Set([...Object.keys(doc.items), ...Object.keys(base)]);
      const applied: string[] = [];
      for (const k of keys) {
        const rem = doc.items[k];
        const loc = base[k];
        if (loc && (!rem || loc.ts > rem.ts)) {
          // lokale Änderung ist neuer: weitergeben
          merged[k] = loc.del ? { ts: loc.ts, del: true } : { ts: loc.ts, del: false, val: cur[k] };
          push = true;
        } else if (rem && (!loc || loc.ts !== rem.ts || loc.del !== rem.del)) {
          // Eintrag eines anderen Geräts ist neuer (oder neu): übernehmen
          this.apply(k, rem);
          base[k] = { h: '', ts: rem.ts, del: rem.del };
          applied.push(k);
          appliedAny = true;
        }
      }
      if (applied.length) {
        const after = collect(this.h.state()); // Darstellung kann je Client leicht abweichen: lokalen Stand als Referenz nehmen
        for (const k of applied) if (!base[k].del && k in after) base[k].h = stable(after[k]);
      }
      for (const [k, it] of Object.entries(merged)) if (it.del && Date.now() - it.ts > TOMBSTONE_MS) delete merged[k];
      if (!push) {
        s.sync.version = r.version;
        return appliedAny;
      }
      try {
        const out = await this.h.api().call<{ version: number }>('PUT', '/v1/sync', { base_version: r.version, data: this.seal({ v: 1, items: merged }) });
        s.sync.version = out.version;
        return appliedAny;
      } catch (e) {
        if (e instanceof ApiError && e.status === 409) continue; // jemand war schneller: neu abgleichen
        throw e;
      }
    }
    return appliedAny;
  }

  /** Fremden Eintrag in den lokalen Zustand übernehmen. */
  private apply(k: string, it: Item): void {
    const s = this.h.state();
    const i = k.indexOf(':');
    const kind = k.slice(0, i);
    const name = k.slice(i + 1);
    const on = !it.del;
    const list = (arr: string[]) => {
      const j = arr.indexOf(name);
      if (on && j < 0) arr.push(name);
      if (!on && j >= 0) arr.splice(j, 1);
    };
    switch (kind) {
      case 'chan':
        this.h.applyChannel(name, on ? (it.val as { server: string; key: string; title: string; createdAt: number }) : null);
        break;
      case 'set':
        if (name === 'avatar') s.me.avatar = on && typeof it.val === 'string' ? it.val : undefined;
        else if (on) (s as unknown as Record<string, unknown>)[name] = it.val;
        break;
      case 'blockU': list(s.blockedUsers); break;
      case 'blockS': list(s.blockedServers); break;
      case 'allowU': list(s.allowUsers); break;
      case 'allowS': list(s.allowServers); break;
      case 'contact':
        if (on) {
          const v = it.val as { ik: string; verified: boolean };
          const c: Contact | undefined = s.contacts[name];
          if (c) { c.ik = v.ik; c.verified = v.verified; } else s.contacts[name] = { address: name, ik: v.ik, verified: v.verified };
        } else delete s.contacts[name];
        break;
    }
    this.h.notify();
  }
}

export type { ChannelState };
