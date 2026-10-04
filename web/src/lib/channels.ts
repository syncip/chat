/**
 * Öffentliche Kanäle (siehe docs/CHANNELS.md): serverunterstützt, Kanalschlüssel nur im Link-Fragment.
 * Beiträge sind mit dem Kanalschlüssel verschlüsselt und mit dem Konto-Schlüssel (AIK) des Absenders signiert.
 */
import { ApiError } from './api';
import type { Core } from './core';
import type { AppState, ChannelPolicy, ChannelState, ChPost, ChEvent, Part } from './types';
import { b64, baseUrl, dec, enc, hex, randomBytes, sha256, unb64 } from './util';

const KIND_CHANNEL = 3;
const TITLE_GID = enc.encode('title');

export interface ChannelLink {
  s: string; // Server
  c: string; // Kanal-ID
  k: string; // Kanalschlüssel (base64)
}

const urlSafe = (s: string) => s.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const unUrlSafe = (s: string) => {
  let p = s.replace(/-/g, '+').replace(/_/g, '/');
  while (p.length % 4) p += '=';
  return p;
};

export function encodeChannelLink(l: ChannelLink): string {
  return urlSafe(b64(enc.encode(JSON.stringify(l))));
}

export function decodeChannelLink(text: string): ChannelLink {
  const m = /#\/join\/([A-Za-z0-9_-]+)/.exec(text) ?? /^([A-Za-z0-9_-]+)$/.exec(text.trim());
  if (!m) throw new Error('Ungültiger Kanal-Link');
  const j = JSON.parse(dec.decode(unb64(unUrlSafe(m[1])))) as ChannelLink;
  if (!j.s || !j.c || typeof j.k !== 'string') throw new Error('Ungültiger Kanal-Link');
  return j;
}

export class NeedsCaptcha extends Error {
  constructor(public token: string, public image: string) {
    super('Captcha erforderlich');
  }
}

export interface ChannelInfo {
  id: string;
  title: string;
  policy: ChannelPolicy;
  members: number;
}

interface LogResponse {
  me: ChannelState['me'];
  policy: ChannelPolicy;
  title_enc: string;
  entries: RawEntry[];
}

interface RawEntry {
  seq: number;
  type: 'post' | 'event';
  ts: number;
  address: string;
  ik: string;
  post_id?: string;
  epoch?: number;
  deleted?: boolean;
  data?: string;
  sig?: string;
  hook?: string;
  kind?: string;
  target?: string;
  meta?: string;
}

/** Signierter Zugriff auf einen Kanal (Authorization: Chan-Sig, Signatur mit dem Konto-Schlüssel). */
export class ChannelApi {
  readonly base: string;
  constructor(private server: string, private id: string, private sign: (d: Uint8Array) => Uint8Array, private ik: Uint8Array) {
    this.base = baseUrl(server);
  }

  private async header(method: string, uri: string, bodyHash: string): Promise<Record<string, string>> {
    const ts = Math.floor(Date.now() / 1000).toString();
    const nonce = b64(randomBytes(12));
    const sig = b64(this.sign(enc.encode(`CHAT-CHAN-V1\n${this.server}\n${method}\n${uri}\n${ts}\n${nonce}\n${bodyHash}`)));
    return { ik: b64(this.ik), ts, nonce, sig };
  }

  async call<T = unknown>(method: string, path: string, body?: unknown): Promise<T> {
    const uri = `/v1/channels/${this.id}${path}`;
    const raw = body === undefined ? new Uint8Array() : enc.encode(JSON.stringify(body));
    const h = await this.header(method, uri, hex(await sha256(raw)));
    let res: Response;
    try {
      res = await fetch(this.base + uri, {
        method,
        headers: {
          Authorization: `Chan-Sig ik=${h.ik},ts=${h.ts},nonce=${h.nonce},sig=${h.sig}`,
          ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        },
        body: body === undefined ? undefined : (raw as BodyInit),
        referrerPolicy: 'no-referrer',
        cache: 'no-store',
        credentials: 'omit',
      });
    } catch {
      throw new Error(`Verbindung zu ${this.base} fehlgeschlagen.`);
    }
    if (!res.ok) {
      let msg = res.statusText;
      try {
        msg = ((await res.json()) as { error?: string }).error ?? msg;
      } catch {
        /* kein JSON */
      }
      throw new ApiError(res.status, msg);
    }
    return (await res.json()) as T;
  }

  async wsAuth(): Promise<string> {
    return JSON.stringify(await this.header('GET', `/v1/channels/${this.id}/stream`, 'WS'));
  }

  wsUrl(): string {
    return `${this.base.replace(/^http/, 'ws')}/v1/channels/${this.id}/stream`;
  }
}

/** Öffentliche (unsignierte) Abfragen. */
export async function publicChannelInfo(server: string, id: string): Promise<{ title_enc: string; policy: ChannelPolicy; members: number }> {
  const res = await fetch(`${baseUrl(server)}/v1/channels/${encodeURIComponent(id)}`, { referrerPolicy: 'no-referrer', credentials: 'omit', cache: 'no-store' });
  if (!res.ok) throw new Error(res.status === 404 ? 'Kanal nicht gefunden.' : `Fehler ${res.status}`);
  return res.json();
}

async function fetchCaptcha(server: string, id: string, ik: Uint8Array): Promise<{ token: string; image: string }> {
  const res = await fetch(`${baseUrl(server)}/v1/channels/${encodeURIComponent(id)}/captcha?ik=${encodeURIComponent(b64(ik))}`, {
    referrerPolicy: 'no-referrer', credentials: 'omit', cache: 'no-store',
  });
  if (!res.ok) throw new Error('Captcha nicht verfügbar');
  return res.json();
}

/** Proof-of-Work für Kanal-Beitritt: sha256(id ":" ik ":" nonce) mit `bits` führenden Null-Bits. */
export async function solveChannelPow(id: string, ik: Uint8Array, bits: number): Promise<string> {
  const ikb = b64(ik);
  for (let i = 0; ; i++) {
    const nonce = i.toString(36);
    const h = await sha256(enc.encode(`${id}:${ikb}:${nonce}`));
    let n = 0;
    for (const byte of h) {
      if (byte === 0) {
        n += 8;
        continue;
      }
      n += Math.clz32(byte) - 24;
      break;
    }
    if (n >= bits) return nonce;
  }
}

export interface Host {
  core: Core;
  state(): AppState;
  sign(d: Uint8Array): Uint8Array;
  ik(): Uint8Array;
  address(): string;
  notify(): void;
  /** Neuer Beitrag eines anderen (für den Benachrichtigungston). */
  incoming(): void;
  /** Heimatserver-Domain und Name des Kontos (zum Anlegen von Kanälen). */
  homeCall<T>(method: string, uri: string, body?: unknown): Promise<T>;
}

/** Kanal-Logik: Anlegen, Beitreten, Synchronisieren, Posten, Moderation. */
export class ChannelManager {
  private sockets = new Map<string, WebSocket>();
  private timers = new Map<string, ReturnType<typeof setTimeout>>();
  private syncing = new Set<string>();
  private again = new Set<string>(); // während eines laufenden Abgleichs eingetroffene Ereignisse
  private closed = true;

  constructor(private h: Host) {}

  get channels(): Record<string, ChannelState> {
    const s = this.h.state();
    return (s.channels ??= {});
  }

  private api(c: { server: string; id: string }): ChannelApi {
    return new ChannelApi(c.server, c.id, this.h.sign, this.h.ik());
  }

  private seal(key: string, gid: Uint8Array, payload: Uint8Array): Uint8Array {
    if (!key) return payload; // öffentlicher Kanal: unverschlüsselt
    return this.h.core.envelopeSeal(unb64(key), KIND_CHANNEL, gid, new Uint8Array(), payload);
  }

  private open(key: string, gid: Uint8Array, blob: Uint8Array): Uint8Array | null {
    if (!key) return blob;
    try {
      const r = this.h.core.envelopeOpen(unb64(key), blob) as [number, Uint8Array, Uint8Array, Uint8Array];
      if (r[0] !== KIND_CHANNEL || b64(r[1]) !== b64(gid)) return null;
      return r[3];
    } catch {
      return null;
    }
  }

  private title(key: string, enc64: string): string {
    if (!key) return dec.decode(unb64(enc64));
    const p = this.open(key, TITLE_GID, unb64(enc64));
    return p ? dec.decode(p) : '(unbekannt)';
  }

  link(id: string): string {
    const c = this.channels[id];
    return `${location.origin}/#/join/${encodeChannelLink({ s: c.server, c: c.id, k: c.key })}`;
  }

  /** Öffentlicher Lese-Link (ohne Konto) – nur für öffentliche Kanäle. */
  publicLink(id: string): string {
    const c = this.channels[id];
    return `${location.origin}/#/c/${urlSafe(b64(enc.encode(JSON.stringify({ s: c.server, c: c.id }))))}`;
  }

  // ---------- Webhooks (ntfy-kompatibel) ----------

  async hooks(id: string): Promise<{ id: string; name: string; created_at: number; last_used: number }[]> {
    return (await this.api(this.channels[id]).call<{ hooks: { id: string; name: string; created_at: number; last_used: number }[] }>('GET', '/hooks')).hooks;
  }

  /** Legt einen Webhook an. Bei nicht-öffentlichen Kanälen erhält der Server den Kanalschlüssel (er muss Webhook-Beiträge selbst verschlüsseln). */
  async createHook(id: string, name: string): Promise<{ id: string; name: string; url: string }> {
    const c = this.channels[id];
    const r = await this.api(c).call<{ id: string; name: string; token: string }>('POST', '/hooks', { name, ...(c.key ? { key: c.key } : {}) });
    return { id: r.id, name: r.name, url: `${baseUrl(c.server)}/h/${r.token}` };
  }

  async deleteHook(id: string, hookId: string): Promise<void> {
    await this.api(this.channels[id]).call('DELETE', `/hooks/${hookId}`);
    await this.sync(id);
  }

  // ---------- Verwaltung ----------

  async create(title: string, policy0: ChannelPolicy, isPublic = false): Promise<string> {
    const policy: ChannelPolicy = { ...policy0, public: isPublic };
    const key = isPublic ? '' : b64(this.h.core.envelopeKey());
    const titleEnc = b64(this.seal(key, TITLE_GID, enc.encode(title.trim() || 'Kanal')));
    const r = await this.h.homeCall<{ id: string }>('POST', '/v1/channels', { title_enc: titleEnc, policy });
    const server = this.h.state().me.domain;
    this.channels[r.id] = this.blank(r.id, server, key, title.trim() || 'Kanal', policy);
    this.channels[r.id].me = { ik: '', address: this.h.address(), role: 'owner', status: 'active', joined_at: 0, muted_until: 0, can_write: true };
    this.h.notify();
    await this.sync(r.id);
    this.watch(r.id);
    return r.id;
  }

  private blank(id: string, server: string, key: string, title: string, policy: ChannelPolicy): ChannelState {
    return {
      id, server, key, title, policy, posts: [], events: [], cursor: 0, unread: 0, createdAt: Date.now(),
      me: { ik: '', address: this.h.address(), role: 'member', status: 'pending', joined_at: 0, muted_until: 0, can_write: false },
    };
  }

  async preview(linkText: string): Promise<ChannelInfo & { link: ChannelLink }> {
    const link = decodeChannelLink(linkText);
    const i = await publicChannelInfo(link.s, link.c);
    return { id: link.c, title: this.title(link.k, i.title_enc), policy: i.policy, members: i.members, link };
  }

  /** Beitreten. Bei Captcha-Kanälen wirft die Methode `NeedsCaptcha`, danach erneut mit `captcha` aufrufen. */
  async join(linkText: string, captcha?: { token: string; answer: string }): Promise<string> {
    const link = decodeChannelLink(linkText);
    const existing = this.channels[link.c];
    if (existing && existing.me.status !== 'banned') return link.c;
    const info = await publicChannelInfo(link.s, link.c);
    const body: Record<string, unknown> = { address: this.h.address() };
    if (info.policy.join_mode === 'pow') {
      body.pow_nonce = await solveChannelPow(link.c, this.h.ik(), info.policy.pow_bits);
    } else if (info.policy.join_mode === 'captcha') {
      if (!captcha) {
        const c = await fetchCaptcha(link.s, link.c, this.h.ik());
        throw new NeedsCaptcha(c.token, c.image);
      }
      body.captcha_token = captcha.token;
      body.captcha_answer = captcha.answer;
    }
    const r = await new ChannelApi(link.s, link.c, this.h.sign, this.h.ik()).call<{ me: ChannelState['me'] }>('POST', '/join', body);
    const ch = this.blank(link.c, link.s, link.k, this.title(link.k, info.title_enc), info.policy);
    ch.me = r.me;
    this.channels[link.c] = ch;
    this.h.notify();
    await this.sync(link.c);
    this.watch(link.c);
    return link.c;
  }

  /** Kanal aus dem Konto-Sync übernehmen (dasselbe Konto ist serverseitig bereits Mitglied/Besitzer). */
  adopt(id: string, v: { server: string; key: string; title: string; createdAt: number }): void {
    if (this.channels[id]) return;
    const ch = this.blank(id, v.server, v.key, v.title, { join_mode: 'open', pow_bits: 0, probation_seconds: 0, members_can_write: false, slow_mode_seconds: 0, public: !v.key });
    ch.createdAt = v.createdAt;
    this.channels[id] = ch;
    this.h.notify();
    void this.sync(id).catch(() => undefined);
    this.watch(id);
  }

  /** Kanal lokal entfernen (Löschung auf einem anderen Gerät). */
  drop(id: string): void {
    if (!this.channels[id]) return;
    this.unwatch(id);
    delete this.channels[id];
    this.h.notify();
  }

  async leave(id: string): Promise<void> {
    const c = this.channels[id];
    if (!c) return;
    if (c.me.role !== 'owner' && c.me.status !== 'banned') {
      try {
        await this.api(c).call('POST', '/leave');
      } catch {
        /* offline: lokal trotzdem entfernen */
      }
    }
    this.unwatch(id);
    delete this.channels[id];
    this.h.notify();
  }

  async remove(id: string): Promise<void> {
    const c = this.channels[id];
    await this.api(c).call('DELETE', '');
    this.unwatch(id);
    delete this.channels[id];
    this.h.notify();
  }

  async update(id: string, opts: { title?: string; policy: ChannelPolicy }): Promise<void> {
    const c = this.channels[id];
    const body: Record<string, unknown> = { policy: opts.policy };
    if (opts.title && opts.title !== c.title) body.title_enc = b64(this.seal(c.key, TITLE_GID, enc.encode(opts.title)));
    await this.api(c).call('PUT', '/settings', body);
    await this.sync(id);
  }

  async mod(id: string, body: { action: string; target?: string; post_id?: string; role?: string; seconds?: number }): Promise<void> {
    await this.api(this.channels[id]).call('POST', '/mod', body);
    await this.sync(id);
  }

  async members(id: string, status?: string): Promise<ChannelState['me'][]> {
    const r = await this.api(this.channels[id]).call<{ members: ChannelState['me'][] }>('GET', `/members${status ? `?status=${status}` : ''}`);
    return r.members;
  }

  // ---------- Beiträge ----------

  async post(id: string, parts: Part[]): Promise<void> {
    const c = this.channels[id];
    if (!c.me.can_write) throw new Error('Du darfst in diesem Kanal nicht schreiben.');
    const postId = urlSafe(b64(randomBytes(12)));
    const ts = Date.now();
    const data = this.seal(c.key, enc.encode(id), enc.encode(JSON.stringify({ v: 1, parts })));
    const msg = `CHAT-POST-V1\n${id}\n${postId}\n${ts}\n0\n${hex(await sha256(data))}`;
    const sig = this.h.sign(enc.encode(msg));
    await this.api(c).call('POST', '/posts', { post_id: postId, ts, epoch: 0, data: b64(data), sig: b64(sig) });
    await this.sync(id);
  }

  private async ingest(c: ChannelState, e: RawEntry): Promise<void> {
    if (e.type === 'post') {
      if (c.posts.some((p) => p.id === e.post_id)) return;
      const post: ChPost = { id: e.post_id!, seq: e.seq, ts: e.ts, from: e.address, ik: e.ik, parts: [], deleted: !!e.deleted, hook: e.hook };
      if (!e.deleted && e.data && (e.sig || e.hook)) {
        const data = unb64(e.data);
        const msg = `CHAT-POST-V1\n${c.id}\n${e.post_id}\n${e.ts}\n${e.epoch ?? 0}\n${hex(await sha256(data))}`;
        const okSig = e.hook ? true : this.h.core.ed25519Verify(unb64(e.ik), enc.encode(msg), unb64(e.sig ?? ''));
        const pt = this.open(c.key, enc.encode(c.id), data);
        if (!okSig || !pt) {
          post.bad = true;
        } else {
          try {
            post.parts = (JSON.parse(dec.decode(pt)) as { parts: Part[] }).parts;
          } catch {
            post.bad = true;
          }
        }
      }
      c.posts.push(post);
      if (e.address !== this.h.address()) {
        c.unread++;
        if (c.cursor > 0 || c.posts.length > 0) this.h.incoming(); // nicht beim ersten Laden des Verlaufs
      }
    } else {
      let meta: Record<string, unknown> = {};
      try {
        meta = (JSON.parse(e.meta || '{}') as Record<string, unknown> | null) ?? {};
      } catch {
        /* leer */
      }
      const ev: ChEvent = { seq: e.seq, ts: e.ts, kind: e.kind ?? '', actor: e.address, targetAddress: (meta.target_address as string) ?? '', meta };
      if (e.kind === 'delete') {
        const p = c.posts.find((x) => x.id === meta.post_id);
        if (p) {
          p.deleted = true;
          p.parts = [];
        }
      }
      if (e.kind !== 'created') c.events.push(ev);
      if (c.events.length > 200) c.events.splice(0, c.events.length - 200);
    }
  }

  async sync(id: string): Promise<void> {
    const c = this.channels[id];
    if (!c) return;
    if (this.syncing.has(id)) {
      this.again.add(id);
      return;
    }
    this.syncing.add(id);
    try {
      for (let i = 0; i < 50; i++) {
        let r: LogResponse;
        try {
          r = await this.api(c).call<LogResponse>('GET', `/log?after=${c.cursor}&limit=100`);
        } catch (x) {
          if (x instanceof ApiError && x.status === 403) {
            c.me.status = 'banned';
            c.me.can_write = false;
            this.h.notify();
          }
          throw x;
        }
        c.me = r.me;
        c.policy = r.policy;
        c.title = this.title(c.key, r.title_enc);
        for (const e of r.entries) {
          await this.ingest(c, e);
          c.cursor = e.seq;
        }
        if (c.posts.length > 1000) c.posts.splice(0, c.posts.length - 1000);
        this.h.notify();
        if (r.entries.length < 100) break;
      }
    } finally {
      this.syncing.delete(id);
    }
    if (this.again.delete(id)) await this.sync(id);
  }

  markRead(id: string): void {
    const c = this.channels[id];
    if (c && c.unread) {
      c.unread = 0;
      this.h.notify();
    }
  }

  // ---------- Verbindung ----------

  start(): void {
    this.closed = false;
    for (const id of Object.keys(this.channels)) {
      void this.sync(id).catch(() => {});
      this.watch(id);
    }
  }

  stop(): void {
    this.closed = true;
    for (const id of [...this.sockets.keys()]) this.unwatch(id);
    this.timers.forEach(clearTimeout);
    this.timers.clear();
  }

  /** Öffnet (bzw. erneuert) die Echtzeit-Verbindung zu einem Kanal; Fallback: Polling bei Verbindungsabbruch. */
  watch(id: string, backoff = 2000): void {
    const c = this.channels[id];
    if (this.closed || !c || this.sockets.has(id)) return;
    const api = this.api(c);
    const ws = new WebSocket(api.wsUrl());
    this.sockets.set(id, ws);
    ws.onopen = async () => ws.send(await api.wsAuth());
    ws.onmessage = (ev) => {
      const m = JSON.parse(String(ev.data)) as { type: string };
      if (m.type === 'log' || m.type === 'ready') void this.sync(id).catch(() => {});
    };
    ws.onclose = () => {
      this.sockets.delete(id);
      if (this.closed || !this.channels[id] || this.channels[id].me.status === 'banned') return;
      this.timers.set(id, setTimeout(() => this.watch(id, Math.min(backoff * 2, 60_000)), backoff));
    };
    ws.onerror = () => ws.close();
  }

  private unwatch(id: string): void {
    const t = this.timers.get(id);
    if (t) clearTimeout(t);
    this.timers.delete(id);
    const ws = this.sockets.get(id);
    this.sockets.delete(id);
    if (ws) {
      ws.onclose = null;
      ws.close();
    }
  }
}
