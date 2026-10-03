/**
 * Engine: verbindet Krypto-Kern (WASM), API und lokalen verschlüsselten Zustand.
 * Alle Nachrichteninhalte werden hier ver- und entschlüsselt; der Server sieht nur Blobs.
 */
import { Api, ApiError } from './api';
import { loadCore, type Client, type Core, type Vault } from './core';
import { kv } from './db';
import type {
  AppState, Cap, CapEntry, Conversation, Content, Contact, Envelope, FilterMode, Msg, Part, ServerInfo,
} from './types';
import {
  b64, dec, enc, hex, randomId, sha256, solvePow, splitAddress, unb64, unhex,
} from './util';

const KIND_WELCOME = 1;
const KIND_MLS = 2;
const KP_BATCH = 40;
const KP_LOW = 15;

export interface ContactCard {
  address: string;
  cap: Cap;
}

export function encodeCard(c: ContactCard): string {
  const j = JSON.stringify({ a: c.address, d: c.cap.domain, m: c.cap.mailbox_id, t: c.cap.send_token, k: c.cap.key });
  return b64(enc.encode(j)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function decodeCard(text: string): ContactCard {
  const m = /#\/add\/([A-Za-z0-9_-]+)/.exec(text) ?? /^([A-Za-z0-9_-]+)$/.exec(text.trim());
  if (!m) throw new Error('Ungültiger Kontaktlink');
  let p = m[1].replace(/-/g, '+').replace(/_/g, '/');
  while (p.length % 4) p += '=';
  const j = JSON.parse(dec.decode(unb64(p))) as { a: string; d: string; m: string; t: string; k: string };
  if (!splitAddress(j.a) || !j.d || !j.m || !j.t || !j.k) throw new Error('Ungültiger Kontaktlink');
  return { address: j.a, cap: { domain: j.d, mailbox_id: j.m, send_token: j.t, key: j.k, intro: true } };
}

type Listener = () => void;

export class Engine {
  core!: Core;
  client: Client | null = null;
  private vault: Vault | null = null;
  api: Api | null = null;
  state: AppState | null = null;
  info: ServerInfo | null = null;
  online = false;
  version = 0;
  /** Adresse des lokal gespeicherten (ggf. gesperrten) Kontos. */
  knownAddress: string | null = null;
  /** Eine Zeile für UI-Fehler/Hinweise. */
  notice = '';

  private listeners = new Set<Listener>();
  private ws: WebSocket | null = null;
  private wsTimer: ReturnType<typeof setTimeout> | null = null;
  private saveTimer: ReturnType<typeof setTimeout> | null = null;
  private queue: Promise<void> = Promise.resolve();
  private timers: ReturnType<typeof setInterval>[] = [];
  private closed = true;
  private lastSeq = 0;
  private backoff = 1000;
  private relays: { convId: string; entry: CapEntry; sender: string }[] = [];

  subscribe = (l: Listener) => {
    this.listeners.add(l);
    return () => this.listeners.delete(l);
  };
  getVersion = () => this.version;
  private emit() {
    this.version++;
    this.listeners.forEach((l) => l());
  }

  get unlocked(): boolean {
    return !!this.state;
  }

  async init(): Promise<{ address: string } | null> {
    this.core = await loadCore();
    const meta = await kv.get<{ address: string }>('meta');
    this.knownAddress = meta?.address ?? null;
    return meta ?? null;
  }

  // ---------- Konto ----------

  async createAccount(opts: { server: string; name: string; invite: string; passphrase: string }): Promise<void> {
    const core = this.core;
    const name = opts.name.trim().toLowerCase();
    const api0 = new Api(opts.server.trim().toLowerCase());
    const info = await api0.serverInfo();
    const domain = info.domain;
    if (info.registration === 'closed') throw new Error('Dieser Server nimmt keine Registrierungen an.');
    const client = new core.Client(`${name}@${domain}`);
    const ts = Math.floor(Date.now() / 1000);
    const sig = client.sign(enc.encode(`CHAT-REGISTER-V1\n${domain}\n${name}\n${ts}`));
    const pow = await solvePow(name, ts, info.pow_bits);
    const kps = Array.from(client.keyPackages(KP_BATCH, false) as Uint8Array[]).map(b64);
    const last = b64((client.keyPackages(1, true) as Uint8Array[])[0]);
    const api = new Api(domain);
    const res = await api.register({
      invite: opts.invite.trim(), name, ik: b64(client.identityPublic()), ts, sig: b64(sig), pow,
      keypackages: kps, last_resort: last,
    });
    const introKey = b64(core.envelopeKey());
    this.client = client;
    this.info = info;
    this.state = {
      v: 1,
      me: { address: `${name}@${domain}`, domain, name },
      intro: { mailbox_id: res.intro.mailbox_id, send_token: res.intro.send_token, key: introKey },
      conversations: {}, contacts: {}, mailboxes: { [res.intro.mailbox_id]: introKey },
      blockedUsers: [], blockedServers: [], allowUsers: [], allowServers: [],
      filterMode: 'off', serverSideFilter: false, directSend: false, cursor: 0, outbox: [],
    };
    this.vault = core.Vault.create(opts.passphrase);
    await this.persist();
    await kv.put('meta', { address: this.state.me.address });
    this.knownAddress = this.state.me.address;
    await this.start();
  }

  async unlock(passphrase: string): Promise<void> {
    const blob = await kv.get<Uint8Array>('vault');
    if (!blob) throw new Error('Kein Konto vorhanden.');
    await this.openVault(passphrase, blob);
    await this.start();
  }

  private async openVault(passphrase: string, blob: Uint8Array): Promise<void> {
    const [vault, pt] = this.core.Vault.open(passphrase, blob) as [Vault, Uint8Array];
    const j = JSON.parse(dec.decode(pt)) as { mls: string; app: AppState };
    this.vault = vault;
    this.client = this.core.Client.importState(unb64(j.mls));
    this.state = j.app;
  }

  /** Backup als verschlüsselte Datei (Passphrase frei wählbar). */
  exportBackup(passphrase: string): Uint8Array {
    return this.core.vaultSeal(passphrase, this.serialize());
  }

  async restoreBackup(file: Uint8Array, backupPass: string, newPass: string): Promise<void> {
    await this.openVault(backupPass, file);
    this.vault = this.core.Vault.create(newPass);
    await this.persist();
    await kv.put('meta', { address: this.state!.me.address });
    this.knownAddress = this.state!.me.address;
    await this.start();
  }

  private serialize(): Uint8Array {
    return enc.encode(JSON.stringify({ mls: b64(this.client!.exportState()), app: this.state }));
  }

  private async persist(): Promise<void> {
    if (!this.vault || !this.client || !this.state) return;
    await kv.put('vault', this.vault.seal(this.serialize()));
  }

  /** Zustand speichern (entprellt). */
  private dirty() {
    this.emit();
    if (this.saveTimer) return;
    this.saveTimer = setTimeout(() => {
      this.saveTimer = null;
      void this.persist();
    }, 250);
  }

  private async flush(): Promise<void> {
    if (this.saveTimer) {
      clearTimeout(this.saveTimer);
      this.saveTimer = null;
    }
    await this.persist();
  }

  async lock(): Promise<void> {
    await this.flush();
    this.closed = true;
    this.timers.forEach(clearInterval);
    this.timers = [];
    this.ws?.close();
    this.ws = null;
    if (this.wsTimer) clearTimeout(this.wsTimer);
    this.client?.free();
    this.vault?.free();
    this.client = null;
    this.vault = null;
    this.state = null;
    this.api = null;
    this.online = false;
    this.emit();
  }

  async deleteAccount(): Promise<void> {
    await this.lock();
    await kv.del('vault');
    await kv.del('meta');
    this.knownAddress = null;
    this.emit();
  }

  // ---------- Start / Netzwerk ----------

  private async start(): Promise<void> {
    const s = this.state!;
    this.closed = false;
    this.api = Api.fromClient(s.me.domain, s.me.name, this.client!);
    try {
      this.info = await this.api.serverInfo();
    } catch {
      /* offline: später erneut */
    }
    this.emit();
    this.connect();
    this.timers.push(setInterval(() => this.purgeExpired(), 30_000));
    this.timers.push(setInterval(() => void this.retryOutbox(), 30_000));
  }

  private connect() {
    if (this.closed || !this.api) return;
    const api = this.api;
    const ws = new WebSocket(api.wsUrl());
    this.ws = ws;
    ws.onopen = async () => {
      ws.send(await api.wsAuth());
    };
    ws.onmessage = (ev) => {
      const m = JSON.parse(String(ev.data)) as { type: string; seq?: number; mailbox_id?: string; data?: string };
      if (m.type === 'ready') {
        this.backoff = 1000;
        this.online = true;
        this.emit();
        this.enqueue(() => this.catchUp());
        void this.retryOutbox();
        void this.replenishKeyPackages();
      } else if (m.type === 'message' && m.seq && m.mailbox_id && m.data) {
        const { seq, mailbox_id, data } = m as { seq: number; mailbox_id: string; data: string };
        this.enqueue(() => this.handleRaw(seq, mailbox_id, unb64(data)));
      }
    };
    ws.onclose = () => {
      this.online = false;
      this.emit();
      if (this.closed) return;
      this.backoff = Math.min(this.backoff * 2, 30_000);
      this.wsTimer = setTimeout(() => this.connect(), this.backoff);
    };
    ws.onerror = () => ws.close();
  }

  private enqueue(f: () => Promise<void>) {
    this.queue = this.queue.then(f).catch((e) => console.error('queue', e));
  }

  private async catchUp(): Promise<void> {
    if (!this.api || !this.state) return;
    for (;;) {
      const msgs = await this.api.call<{ seq: number; mailbox_id: string; data: string }[]>(
        'GET', `/v1/messages?after=${this.state.cursor}&limit=100`);
      if (msgs.length === 0) return;
      for (const m of msgs) await this.handleRaw(m.seq, m.mailbox_id, unb64(m.data));
    }
  }

  private async handleRaw(seq: number, mailboxId: string, data: Uint8Array): Promise<void> {
    const s = this.state;
    if (!s || seq <= s.cursor) return;
    try {
      await this.handleIncoming(mailboxId, data);
    } catch (e) {
      console.warn('message dropped', e);
    }
    s.cursor = seq;
    this.lastSeq = seq;
    await this.flush();
    this.emit();
    // Erst nach dem Speichern beim Server löschen.
    this.api?.call('DELETE', `/v1/messages?upto=${seq}`).catch(() => undefined);
  }

  private async replenishKeyPackages(): Promise<void> {
    if (!this.api || !this.client) return;
    try {
      const { count } = await this.api.call<{ count: number }>('GET', '/v1/keypackages/count');
      if (count >= KP_LOW) return;
      const kps = Array.from(this.client.keyPackages(KP_BATCH, false) as Uint8Array[]).map(b64);
      const last = b64((this.client.keyPackages(1, true) as Uint8Array[])[0]);
      await this.api.call('PUT', '/v1/keypackages', { keypackages: kps, last_resort: last });
      await this.flush();
    } catch (e) {
      console.warn('keypackages', e);
    }
  }

  // ---------- Filter ----------

  private isBlocked(address: string): boolean {
    const s = this.state!;
    const a = splitAddress(address);
    if (!a) return true;
    if (s.blockedUsers.includes(address)) return true;
    if (s.blockedServers.includes(a.domain)) return true;
    if (s.filterMode === 'allow') {
      return !(s.allowUsers.includes(address) || s.allowServers.includes(a.domain) || !!s.contacts[address]?.verified);
    }
    return false;
  }

  // ---------- Eingehend ----------

  private async handleIncoming(mailboxId: string, data: Uint8Array): Promise<void> {
    const s = this.state!;
    const key = s.mailboxes[mailboxId];
    if (!key) return;
    let opened: [number, Uint8Array, Uint8Array];
    try {
      opened = this.core.envelopeOpen(unb64(key), data) as [number, Uint8Array, Uint8Array];
    } catch {
      return;
    }
    const [kind, gid, payload] = opened;
    if (kind === KIND_WELCOME) this.handleWelcome(payload);
    else if (kind === KIND_MLS) await this.handleMls(hex(gid), gid, payload);
  }

  private pinMembers(conv: Conversation): void {
    const s = this.state!;
    for (const m of conv.members) {
      if (m.address === s.me.address) continue;
      const c = s.contacts[m.address];
      if (!c) {
        s.contacts[m.address] = { address: m.address, ik: m.ik, verified: false };
      } else if (c.ik !== m.ik) {
        conv.warning = `Der Schlüssel von ${m.address} hat sich geändert. Bitte neu verifizieren.`;
        c.verified = false;
      }
    }
  }

  private readMembers(gid: Uint8Array): { address: string; ik: string }[] {
    return (JSON.parse(this.client!.members(gid)) as { address: string; identity: string }[]).map((m) => ({
      address: m.address,
      ik: m.identity,
    }));
  }

  private handleWelcome(welcome: Uint8Array): void {
    const s = this.state!;
    const gid = this.client!.join(welcome);
    const id = hex(gid);
    if (s.conversations[id]) return;
    const members = this.readMembers(gid);
    const others = members.filter((m) => m.address !== s.me.address);
    // Blockierte oder nicht erlaubte Absender: stillschweigend verwerfen (der Absender erfährt nichts).
    if (others.some((m) => s.blockedUsers.includes(m.address) || s.blockedServers.includes(splitAddress(m.address)?.domain ?? '')) ||
        (s.filterMode === 'allow' && !others.some((m) => !this.isBlocked(m.address)))) {
      this.client!.deleteGroup(gid);
      return;
    }
    const kind = members.length === 2 ? 'dm' : 'group';
    const conv: Conversation = {
      id, kind, title: kind === 'dm' ? others[0]?.address ?? '?' : 'Gruppe', status: 'request', members,
      caps: {}, messages: [], unread: 1, disappearSeconds: 0, createdAt: Date.now(),
    };
    const known = kind === 'dm' && s.contacts[others[0]?.address]?.verified;
    s.conversations[id] = conv;
    this.pinMembers(conv);
    if (known) void this.acceptRequest(id);
  }

  private async handleMls(id: string, gid: Uint8Array, payload: Uint8Array): Promise<void> {
    const s = this.state!;
    const conv = s.conversations[id];
    if (!conv || conv.status === 'left') return;
    const res = JSON.parse(this.client!.process(gid, payload)) as
      | { kind: 'application'; sender: string; envelope: Envelope }
      | { kind: 'commit'; sender: string; removedSelf: boolean }
      | { kind: 'proposal' };
    if (res.kind === 'commit') {
      if (res.removedSelf) {
        conv.status = 'left';
        this.client!.deleteGroup(gid);
      } else {
        conv.members = this.readMembers(gid);
        this.pinMembers(conv);
        if (conv.kind === 'group') this.rebuildCaps(conv);
      }
      return;
    }
    if (res.kind !== 'application') return;
    // Zustand ist fortgeschrieben; blockierte Absender werden erst jetzt verworfen.
    if (this.isBlocked(res.sender)) return;
    this.applyContent(conv, res.sender, res.envelope);
    await this.flushRelays();
  }

  /** Postfach-Verzeichnis in Gruppen vervollständigen: neue Einträge verteilen, dem Neuen die bekannten schicken. */
  private async flushRelays(): Promise<void> {
    const s = this.state!;
    const list = this.relays;
    this.relays = [];
    for (const { convId, entry, sender } of list) {
      const conv = s.conversations[convId];
      if (!conv || conv.status !== 'active') continue;
      const others = conv.members.map((m) => m.address).filter((a) => a !== s.me.address && a !== sender);
      if (others.length) await this.sendContent(conv, { kind: 'directory', entries: [entry] }, others);
      const known: CapEntry[] = Object.entries(conv.caps)
        .filter(([a, k]) => a !== sender && !k.intro)
        .map(([a, k]) => ({ address: a, domain: k.domain, mailbox_id: k.mailbox_id, send_token: k.send_token, key: k.key }));
      if (known.length) await this.sendContent(conv, { kind: 'directory', entries: known }, [sender]);
    }
  }

  private async sendContent(conv: Conversation, content: Content, only: string[]): Promise<void> {
    const gid = unhex(conv.id);
    const ct = this.client!.encryptEnvelope(gid, JSON.stringify(this.newEnvelope(content)));
    await this.sendCt(conv, gid, KIND_MLS, ct, only);
  }

  private rebuildCaps(conv: Conversation) {
    const addrs = new Set(conv.members.map((m) => m.address));
    for (const a of Object.keys(conv.caps)) if (!addrs.has(a)) delete conv.caps[a];
  }

  private applyContent(conv: Conversation, sender: string, env: Envelope): void {
    const s = this.state!;
    const c = env.content;
    // Absender muss aktuelles Mitglied sein (MLS garantiert das, wir prüfen zusätzlich die Adresse).
    if (!conv.members.some((m) => m.address === sender)) return;
    switch (c.kind) {
      case 'message': {
        if (conv.messages.some((m) => m.id === env.id)) return;
        const msg: Msg = {
          id: env.id, from: sender, ts: Math.min(env.ts, Date.now() + 5 * 60_000), parts: c.parts,
          status: 'received', reactions: {},
        };
        if (conv.disappearSeconds > 0) msg.expiresAt = Date.now() + conv.disappearSeconds * 1000;
        conv.messages.push(msg);
        conv.messages.sort((a, b) => a.ts - b.ts);
        conv.unread++;
        break;
      }
      case 'reaction': {
        const m = conv.messages.find((x) => x.id === c.reference);
        if (!m || c.emoji.length > 16) return;
        const list = (m.reactions[c.emoji] ??= []);
        const i = list.indexOf(sender);
        if (i >= 0) list.splice(i, 1);
        else list.push(sender);
        if (list.length === 0) delete m.reactions[c.emoji];
        break;
      }
      case 'edit': {
        const m = conv.messages.find((x) => x.id === c.reference);
        if (m && m.from === sender && !m.deleted) {
          m.parts = c.parts;
          m.edited = true;
        }
        break;
      }
      case 'delete': {
        const m = conv.messages.find((x) => x.id === c.reference);
        if (m && m.from === sender) {
          m.deleted = true;
          m.parts = [];
        }
        break;
      }
      case 'disappear':
        conv.disappearSeconds = Math.max(0, Math.min(c.seconds, 365 * 86400));
        break;
      case 'group_name':
        if (conv.kind === 'group') conv.title = c.name.slice(0, 80);
        break;
      case 'directory':
        for (const e of c.entries) {
          const before = conv.caps[e.address]?.mailbox_id;
          this.mergeCap(conv, sender, e);
          const after = conv.caps[e.address]?.mailbox_id;
          // Neue Selbst-Ankündigung: an die übrigen Mitglieder weiterreichen (sie kennen unser Postfach evtl. noch nicht).
          if (e.address === sender && after && after !== before && conv.status === 'active' && conv.kind === 'group') {
            this.relays.push({ convId: conv.id, entry: e, sender });
          }
        }
        break;
      case 'read':
        break;
    }
    void s;
  }

  /** Caps nur vom Besitzer selbst überschreibbar; für andere gilt „first write wins“ (kein Umleiten durch Dritte). */
  private mergeCap(conv: Conversation, sender: string, e: CapEntry): void {
    if (!splitAddress(e.address) || !e.domain || !e.mailbox_id || !e.send_token || !e.key) return;
    const cur = conv.caps[e.address];
    if (e.address === sender || !cur || cur.intro) {
      if (e.address !== sender && !conv.members.some((m) => m.address === e.address)) return;
      conv.caps[e.address] = { domain: e.domain, mailbox_id: e.mailbox_id, send_token: e.send_token, key: e.key };
    }
  }

  // ---------- Ausgehend ----------

  private async deliver(cap: Cap, blob: Uint8Array): Promise<boolean> {
    const s = this.state!;
    try {
      if (s.directSend) {
        const st = await Api.putDirect(cap.domain, cap.mailbox_id, cap.send_token, blob);
        if (st === 404) return true; // Postfach widerrufen: verwerfen
        if (st >= 200 && st < 300) return true;
        throw new Error(String(st));
      }
      await this.api!.call('POST', '/v1/relay', {
        domain: cap.domain, mailbox_id: cap.mailbox_id, send_token: cap.send_token, data: b64(blob),
      });
      return true;
    } catch (e) {
      if (e instanceof ApiError && (e.status === 404 || e.status === 403 || e.status === 413)) return true; // dauerhaft
      s.outbox.push({ id: randomId(), cap, blob: b64(blob), tries: 0 });
      return false;
    }
  }

  private async retryOutbox(): Promise<void> {
    const s = this.state;
    if (!s || !this.api || s.outbox.length === 0) return;
    const items = s.outbox.splice(0);
    for (const it of items) {
      const before = s.outbox.length;
      const ok = await this.deliver(it.cap, unb64(it.blob));
      if (!ok) {
        const last = s.outbox[before];
        if (last) last.tries = it.tries + 1;
        if (it.tries + 1 >= 200) s.outbox.pop(); // aufgeben
      }
    }
    this.dirty();
  }

  private newEnvelope(content: Content): Envelope {
    return { v: 1, id: randomId(), ts: Date.now(), content };
  }

  /** MLS-verschlüsseln und an alle Mitglieder mit bekanntem Postfach senden. */
  private async broadcast(conv: Conversation, env: Envelope): Promise<number> {
    const gid = unhex(conv.id);
    const ct = this.client!.encryptEnvelope(gid, JSON.stringify(env));
    return this.sendCt(conv, gid, KIND_MLS, ct);
  }

  private async sendCt(conv: Conversation, gid: Uint8Array, kind: number, ct: Uint8Array, only?: string[]): Promise<number> {
    const me = this.state!.me.address;
    let sent = 0;
    for (const m of conv.members) {
      if (m.address === me || (only && !only.includes(m.address))) continue;
      const cap = conv.caps[m.address];
      if (!cap) continue;
      const blob = this.core.envelopeSeal(unb64(cap.key), kind, gid, ct);
      await this.deliver(cap, blob);
      sent++;
    }
    return sent;
  }

  private async newMailbox(): Promise<{ id: string; key: string; token: string }> {
    const r = await this.api!.call<{ mailbox_id: string; send_token: string }>('POST', '/v1/mailboxes');
    const key = b64(this.core.envelopeKey());
    this.state!.mailboxes[r.mailbox_id] = key;
    return { id: r.mailbox_id, key, token: r.send_token };
  }

  /** Eigenes Empfangs-Postfach für die Unterhaltung anlegen (Capability liegt nur im verschlüsselten Vault). */
  private async ensureMailbox(conv: Conversation): Promise<void> {
    if (conv.myMailbox) return;
    const mb = await this.newMailbox();
    conv.myMailbox = { id: mb.id, key: mb.key, token: mb.token };
  }

  /** Eigenes Postfach (plus ggf. weitere bekannte) an Mitglieder verteilen. */
  private async announce(conv: Conversation, extra: CapEntry[] = [], only?: string[]): Promise<void> {
    const s = this.state!;
    if (!conv.myMailbox) await this.ensureMailbox(conv);
    const mb = conv.myMailbox!;
    const me: CapEntry = {
      address: s.me.address, domain: s.me.domain, mailbox_id: mb.id, send_token: mb.token, key: mb.key,
    };
    const env = this.newEnvelope({ kind: 'directory', entries: [me, ...extra] });
    const gid = unhex(conv.id);
    const ct = this.client!.encryptEnvelope(gid, JSON.stringify(env));
    await this.sendCt(conv, gid, KIND_MLS, ct, only);
  }

  // ---------- Öffentliche Aktionen ----------

  /** Link/Karte zum Teilen (Intro-Postfach). */
  myCard(): ContactCard | null {
    const s = this.state;
    if (!s?.intro) return null;
    return {
      address: s.me.address,
      cap: { domain: s.me.domain, mailbox_id: s.intro.mailbox_id, send_token: s.intro.send_token, key: s.intro.key },
    };
  }

  contactLink(): string {
    const c = this.myCard();
    return c ? `${location.origin}/#/add/${encodeCard(c)}` : '';
  }

  async setIntroEnabled(on: boolean): Promise<void> {
    const s = this.state!;
    if (!on && s.intro) {
      await this.api!.call('DELETE', `/v1/mailboxes/${s.intro.mailbox_id}`);
      delete s.mailboxes[s.intro.mailbox_id];
      s.intro = null;
    } else if (on && !s.intro) {
      const mb = await this.newMailbox();
      s.intro = { mailbox_id: mb.id, send_token: mb.token, key: mb.key };
    }
    this.dirty();
  }

  /** Neuen Chat per Kontaktlink starten. */
  async startChat(card: ContactCard): Promise<string> {
    const s = this.state!;
    const addr = card.address;
    if (addr === s.me.address) throw new Error('Das bist du selbst.');
    if (s.blockedUsers.includes(addr)) throw new Error('Dieser Nutzer ist blockiert.');
    const existing = Object.values(s.conversations).find((c) => c.kind === 'dm' && c.members.some((m) => m.address === addr) && c.status !== 'left');
    if (existing) return existing.id;
    const { kp, ik } = await this.fetchKeyPackage(addr);
    const contact = (s.contacts[addr] ??= { address: addr, ik, verified: false });
    if (contact.ik !== ik) throw new Error('Der Schlüssel dieses Kontakts hat sich geändert. Bitte neu verifizieren.');
    contact.intro = card.cap;
    const gid = this.client!.createGroup();
    const [, welcome] = this.client!.addMembers(gid, [kp]) as Uint8Array[];
    const id = hex(gid);
    const conv: Conversation = {
      id, kind: 'dm', title: addr, status: 'active', members: this.readMembers(gid),
      caps: { [addr]: card.cap }, messages: [], unread: 0, disappearSeconds: 0, createdAt: Date.now(),
    };
    s.conversations[id] = conv;
    await this.ensureMailbox(conv);
    await this.flush();
    await this.deliver(card.cap, this.core.envelopeSeal(unb64(card.cap.key), KIND_WELCOME, new Uint8Array(), welcome));
    await this.announce(conv);
    this.dirty();
    return id;
  }

  private async fetchKeyPackage(addr: string): Promise<{ kp: Uint8Array; ik: string }> {
    const r = await this.api!.call<{ keypackage: string; ik: string }>('GET', `/v1/resolve/${encodeURIComponent(addr)}/keypackage`);
    const kp = unb64(r.keypackage);
    const id = JSON.parse(this.client!.keyPackageIdentity(kp)) as { address: string; identity: string };
    if (id.address !== addr) throw new Error('Der Server hat ein KeyPackage für eine andere Adresse geliefert.');
    if (id.identity !== hex(unb64(r.ik))) throw new Error('Schlüssel stimmt nicht überein (möglicher Manipulationsversuch).');
    return { kp, ik: id.identity };
  }

  async acceptRequest(id: string): Promise<void> {
    const conv = this.state!.conversations[id];
    if (!conv || conv.status !== 'request') return;
    conv.status = 'active';
    await this.ensureMailbox(conv);
    await this.announce(conv);
    this.dirty();
  }

  async declineRequest(id: string): Promise<void> {
    const conv = this.state!.conversations[id];
    if (!conv) return;
    this.client!.deleteGroup(unhex(id));
    delete this.state!.conversations[id];
    this.dirty();
  }

  /** Gruppe mit bekannten Kontakten anlegen. */
  async createGroup(title: string, addresses: string[]): Promise<string> {
    const s = this.state!;
    if (addresses.length === 0) throw new Error('Mindestens ein Mitglied wählen.');
    const kps: Uint8Array[] = [];
    const caps: Record<string, Cap> = {};
    for (const a of addresses) {
      const cap = this.knownCap(a);
      if (!cap) throw new Error(`${a} ist kein bekannter Kontakt.`);
      const { kp, ik } = await this.fetchKeyPackage(a);
      const c = (s.contacts[a] ??= { address: a, ik, verified: false });
      if (c.ik !== ik) throw new Error(`Schlüssel von ${a} hat sich geändert.`);
      kps.push(kp);
      caps[a] = cap;
    }
    const gid = this.client!.createGroup();
    const [, welcome] = this.client!.addMembers(gid, kps) as Uint8Array[];
    const id = hex(gid);
    const conv: Conversation = {
      id, kind: 'group', title: title.trim() || 'Gruppe', status: 'active', members: this.readMembers(gid),
      caps, messages: [], unread: 0, disappearSeconds: 0, createdAt: Date.now(),
    };
    s.conversations[id] = conv;
    await this.ensureMailbox(conv);
    await this.flush();
    await this.sendCt(conv, gid, KIND_WELCOME, welcome);
    await this.announce(conv);
    await this.broadcast(conv, this.newEnvelope({ kind: 'group_name', name: conv.title }));
    this.dirty();
    return id;
  }

  private knownCap(addr: string): Cap | undefined {
    const s = this.state!;
    for (const c of Object.values(s.conversations)) {
      if (c.kind === 'dm' && c.status === 'active' && c.caps[addr] && !c.caps[addr].intro) return c.caps[addr];
    }
    return s.contacts[addr]?.intro ?? Object.values(s.conversations).map((c) => c.caps[addr]).find(Boolean);
  }

  async addMember(id: string, address: string): Promise<void> {
    const s = this.state!;
    const conv = s.conversations[id];
    if (!conv || conv.kind !== 'group') throw new Error('Keine Gruppe.');
    if (conv.members.some((m) => m.address === address)) throw new Error('Bereits Mitglied.');
    const cap = this.knownCap(address);
    if (!cap) throw new Error(`${address} ist kein bekannter Kontakt.`);
    const { kp, ik } = await this.fetchKeyPackage(address);
    const c = (s.contacts[address] ??= { address, ik, verified: false });
    if (c.ik !== ik) throw new Error('Schlüssel hat sich geändert.');
    const gid = unhex(id);
    const [commit, welcome] = this.client!.addMembers(gid, [kp]) as Uint8Array[];
    const others = conv.members.filter((m) => m.address !== s.me.address).map((m) => m.address);
    conv.members = this.readMembers(gid);
    // Commit an bisherige Mitglieder, Welcome + Verzeichnis an das neue Mitglied.
    await this.sendCt(conv, gid, KIND_MLS, commit, others);
    conv.caps[address] = cap;
    await this.sendCt(conv, gid, KIND_WELCOME, welcome, [address]);
    const dir: CapEntry[] = Object.entries(conv.caps)
      .filter(([a]) => a !== address)
      .map(([a, k]) => ({ address: a, ...k }));
    await this.announce(conv, dir, [address]);
    await this.broadcast(conv, this.newEnvelope({ kind: 'group_name', name: conv.title }));
    this.dirty();
  }

  async removeMember(id: string, address: string): Promise<void> {
    const s = this.state!;
    const conv = s.conversations[id];
    if (!conv || conv.kind !== 'group') return;
    const gid = unhex(id);
    const commit = this.client!.removeMembers(gid, [address]);
    const targets = conv.members.map((m) => m.address).filter((a) => a !== s.me.address);
    await this.sendCt(conv, gid, KIND_MLS, commit, targets);
    conv.members = this.readMembers(gid);
    delete conv.caps[address];
    this.dirty();
  }

  /** Lokal verlassen: Gruppenzustand löschen, History bleibt bis zum Löschen. */
  leaveConversation(id: string): void {
    const conv = this.state!.conversations[id];
    if (!conv) return;
    try {
      this.client!.deleteGroup(unhex(id));
    } catch {
      /* schon gelöscht */
    }
    conv.status = 'left';
    this.dirty();
  }

  deleteConversation(id: string): void {
    const s = this.state!;
    const conv = s.conversations[id];
    if (!conv) return;
    if (conv.status !== 'left') this.leaveConversation(id);
    if (conv.myMailbox) {
      this.api?.call('DELETE', `/v1/mailboxes/${conv.myMailbox.id}`).catch(() => undefined);
      delete s.mailboxes[conv.myMailbox.id];
    }
    delete s.conversations[id];
    this.dirty();
  }

  /** Neue Schlüssel für die Gruppe (Post-Compromise-Security). */
  async rotateKeys(id: string): Promise<void> {
    const s = this.state!;
    const conv = s.conversations[id];
    if (!conv) return;
    const gid = unhex(id);
    const commit = this.client!.updateKeys(gid);
    await this.sendCt(conv, gid, KIND_MLS, commit);
    this.dirty();
    void s;
  }

  async sendMessage(
    id: string,
    o: { text?: string; code?: { lang: string; body: string }; quote?: Msg; files?: File[] },
  ): Promise<void> {
    const s = this.state!;
    const conv = s.conversations[id];
    if (!conv || conv.status !== 'active') throw new Error('Unterhaltung nicht aktiv.');
    const lim = this.info?.limits;
    const parts: Part[] = [];
    if (o.quote) parts.push({ type: 'quote', reference: o.quote.id, snippet: snippetOf(o.quote).slice(0, 200) });
    if (o.text?.trim()) {
      if (lim && enc.encode(o.text).length > lim.max_message_text) throw new Error('Text zu lang.');
      parts.push({ type: 'text', body: o.text });
    }
    if (o.code?.body.trim()) {
      if (lim && enc.encode(o.code.body).length > lim.max_message_text) throw new Error('Codeblock zu lang.');
      parts.push({ type: 'code', lang: o.code.lang.trim().slice(0, 30), body: o.code.body });
    }
    const files = o.files ?? [];
    if (lim) {
      if (files.length > lim.max_message_attachments) throw new Error(`Maximal ${lim.max_message_attachments} Dateien pro Nachricht.`);
      if (files.reduce((a, f) => a + f.size, 0) > lim.max_message_total_size) throw new Error('Nachricht überschreitet die Gesamtgröße.');
      for (const f of files) if (f.size > lim.max_file_size) throw new Error(`${f.name}: Datei zu groß.`);
    }
    for (const f of files) parts.push(await this.uploadFile(f));
    if (parts.length === 0) return;
    const env = this.newEnvelope({ kind: 'message', parts });
    const msg: Msg = { id: env.id, from: s.me.address, ts: env.ts, parts, status: 'sending', reactions: {} };
    if (conv.disappearSeconds > 0) msg.expiresAt = Date.now() + conv.disappearSeconds * 1000;
    conv.messages.push(msg);
    this.dirty();
    const n = await this.broadcast(conv, env);
    msg.status = n > 0 ? 'sent' : 'failed';
    this.dirty();
  }

  private async uploadFile(f: File): Promise<Part> {
    const data = new Uint8Array(await f.arrayBuffer());
    const kn = this.core.fileRandomKey();
    const key = kn.slice(0, 32);
    const nonce = kn.slice(32);
    const ct = this.core.fileEncrypt(key, nonce, data);
    const blobId = await this.api!.uploadBlob(ct);
    return {
      type: 'file', blob_id: blobId, blob_server: this.state!.me.domain, key: b64(key), nonce: b64(nonce),
      name: f.name.slice(0, 200), mime: f.type || 'application/octet-stream', size: f.size,
      sha256: hex(await sha256(data)),
    };
  }

  /** Datei laden und entschlüsseln. Nur auf ausdrückliche Nutzeraktion (verhindert IP-Leaks durch fremde Server). */
  async downloadFile(p: Extract<Part, { type: 'file' }>): Promise<Blob> {
    if (!splitAddress('x1@' + p.blob_server)) throw new Error('Ungültiger Server.');
    const ct = await Api.downloadBlob(p.blob_server, p.blob_id);
    const data = this.core.fileDecrypt(unb64(p.key), unb64(p.nonce), ct);
    if (data.length !== p.size || hex(await sha256(data)) !== p.sha256) throw new Error('Datei beschädigt oder manipuliert.');
    const safeImage = /^image\/(png|jpeg|gif|webp)$/.test(p.mime);
    return new Blob([data as BlobPart], { type: safeImage ? p.mime : 'application/octet-stream' });
  }

  async react(id: string, ref: string, emoji: string): Promise<void> {
    const conv = this.state!.conversations[id];
    const m = conv?.messages.find((x) => x.id === ref);
    if (!conv || !m) return;
    this.applyContent(conv, this.state!.me.address, this.newEnvelope({ kind: 'reaction', reference: ref, emoji }));
    await this.broadcast(conv, this.newEnvelope({ kind: 'reaction', reference: ref, emoji }));
    this.dirty();
  }

  async editMessage(id: string, ref: string, text: string): Promise<void> {
    const s = this.state!;
    const conv = s.conversations[id];
    const m = conv?.messages.find((x) => x.id === ref);
    if (!conv || !m || m.from !== s.me.address || m.deleted) return;
    const parts = m.parts.map((p) => (p.type === 'text' ? { ...p, body: text } : p));
    if (!parts.some((p) => p.type === 'text')) parts.push({ type: 'text', body: text });
    m.parts = parts;
    m.edited = true;
    await this.broadcast(conv, this.newEnvelope({ kind: 'edit', reference: ref, parts }));
    this.dirty();
  }

  async deleteMessage(id: string, ref: string): Promise<void> {
    const s = this.state!;
    const conv = s.conversations[id];
    const m = conv?.messages.find((x) => x.id === ref);
    if (!conv || !m || m.from !== s.me.address) return;
    m.deleted = true;
    m.parts = [];
    await this.broadcast(conv, this.newEnvelope({ kind: 'delete', reference: ref }));
    this.dirty();
  }

  async setDisappear(id: string, seconds: number): Promise<void> {
    const conv = this.state!.conversations[id];
    if (!conv) return;
    conv.disappearSeconds = seconds;
    await this.broadcast(conv, this.newEnvelope({ kind: 'disappear', seconds }));
    this.dirty();
  }

  markRead(id: string): void {
    const conv = this.state?.conversations[id];
    if (conv && conv.unread) {
      conv.unread = 0;
      this.dirty();
    }
  }

  private purgeExpired(): void {
    const s = this.state;
    if (!s) return;
    const now = Date.now();
    let changed = false;
    for (const c of Object.values(s.conversations)) {
      const keep = c.messages.filter((m) => !m.expiresAt || m.expiresAt > now);
      if (keep.length !== c.messages.length) {
        c.messages = keep;
        changed = true;
      }
    }
    if (changed) this.dirty();
  }

  // ---------- Kontakte, Blockieren, Filter ----------

  verifyContact(address: string, verified: boolean): void {
    const c = this.state!.contacts[address];
    if (!c) return;
    c.verified = verified;
    if (verified) for (const conv of Object.values(this.state!.conversations)) if (conv.members.some((m) => m.address === address)) delete conv.warning;
    this.dirty();
  }

  safetyNumber(address: string): string | null {
    const c = this.state!.contacts[address];
    if (!c) return null;
    return this.core.pairSafetyNumber(this.client!.identityPublic(), unhex(c.ik));
  }

  async blockUser(address: string): Promise<void> {
    const s = this.state!;
    if (!s.blockedUsers.includes(address)) s.blockedUsers.push(address);
    // DM-Postfach widerrufen: der Server wirft weitere Einwürfe des Kontakts ab.
    for (const c of Object.values(s.conversations)) {
      if (c.kind === 'dm' && c.members.some((m) => m.address === address) && c.myMailbox) {
        await this.api!.call('DELETE', `/v1/mailboxes/${c.myMailbox.id}`).catch(() => undefined);
        delete s.mailboxes[c.myMailbox.id];
        c.myMailbox = undefined;
      }
    }
    this.dirty();
  }

  unblockUser(address: string): void {
    const s = this.state!;
    s.blockedUsers = s.blockedUsers.filter((a) => a !== address);
    this.dirty();
  }

  async blockServer(domain: string): Promise<void> {
    const s = this.state!;
    const d = domain.trim().toLowerCase();
    if (d && !s.blockedServers.includes(d)) s.blockedServers.push(d);
    this.dirty();
    await this.syncServerFilter();
  }

  async unblockServer(domain: string): Promise<void> {
    const s = this.state!;
    s.blockedServers = s.blockedServers.filter((d) => d !== domain);
    this.dirty();
    await this.syncServerFilter();
  }

  async setFilterMode(mode: FilterMode): Promise<void> {
    this.state!.filterMode = mode;
    this.dirty();
    await this.syncServerFilter();
  }

  async setAllow(kind: 'user' | 'server', value: string, on: boolean): Promise<void> {
    const s = this.state!;
    const v = value.trim().toLowerCase();
    const key = kind === 'user' ? 'allowUsers' : 'allowServers';
    s[key] = on ? Array.from(new Set([...s[key], v])) : s[key].filter((x) => x !== v);
    this.dirty();
    await this.syncServerFilter();
  }

  async setServerSideFilter(on: boolean): Promise<void> {
    this.state!.serverSideFilter = on;
    this.dirty();
    await this.syncServerFilter();
  }

  async setDirectSend(on: boolean): Promise<void> {
    this.state!.directSend = on;
    this.dirty();
  }

  /** Optional: gehashte Domain-Liste beim Home-Server hinterlegen (spart Bandbreite, verrät aber die Liste). */
  private async syncServerFilter(): Promise<void> {
    const s = this.state!;
    if (!this.api) return;
    const mode = !s.serverSideFilter ? 'off' : s.filterMode === 'allow' ? 'allow' : s.blockedServers.length ? 'block' : 'off';
    const list = mode === 'allow' ? s.allowServers : s.blockedServers;
    const hashes = await Promise.all(list.map(async (d) => b64(await sha256(enc.encode(d)))));
    await this.api.call('PUT', '/v1/filters', { mode, domains: hashes }).catch((e) => {
      this.notice = `Server-Filter konnte nicht gesetzt werden: ${(e as Error).message}`;
    });
    this.emit();
  }

  async quota(): Promise<{ used: number; quota: number }> {
    return this.api!.call('GET', '/v1/quota');
  }

  async createInvite(): Promise<string> {
    return (await this.api!.call<{ invite: string }>('POST', '/v1/invites', {})).invite;
  }

  get lastSeqSeen() {
    return this.lastSeq;
  }
}

export function snippetOf(m: Msg): string {
  for (const p of m.parts) {
    if (p.type === 'text') return p.body;
    if (p.type === 'code') return p.body;
    if (p.type === 'file') return `📎 ${p.name}`;
  }
  return m.deleted ? 'Nachricht gelöscht' : '';
}

export type { Contact };
