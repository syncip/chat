/**
 * Engine: verbindet Krypto-Kern (WASM), API und lokalen verschlüsselten Zustand.
 * Alle Nachrichteninhalte werden hier ver- und entschlüsselt; der Server sieht nur Blobs.
 */
import { Api, ApiError } from './api';
import { ChannelManager } from './channels';
import { AccountSync } from './sync';
import { clearSession } from './session';
import { playNotify } from './sound';
import { loadCore, type Client, type Core, type Vault } from './core';
import { kv } from './db';
import type {
  AppState, Cap, CapEntry, SecurityAlert, Conversation, Content, Contact, DeviceInfo, Envelope, FilterMode, Msg, Part, ServerInfo,
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
  /** Dieses Konto ist Administrator des Servers (der erste registrierte Nutzer). */
  isAdmin = false;
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
  private receipts: { convId: string; kind: 'delivered' | 'read'; ids: string[] }[] = [];
  private reconciling = false;
  readonly channels: ChannelManager;
  private accountSync: AccountSync;
  private syncTimer: ReturnType<typeof setTimeout> | null = null;
  private lastSyncSig = '';

  constructor() {
    const self = this;
    this.channels = new ChannelManager({
      get core() {
        return self.core;
      },
      state: () => self.state!,
      sign: (d) => self.client!.signAccount(d),
      ik: () => self.client!.identityPublic(),
      address: () => self.state!.me.address,
      notify: () => self.dirty(),
      incoming: () => playNotify(),
      homeCall: (m, u, b) => self.api!.call(m, u, b),
    });
    this.accountSync = new AccountSync({
      get core() {
        return self.core;
      },
      state: () => self.state!,
      api: () => self.api!,
      syncKey: () => self.client!.syncKey(),
      applyChannel: (id, v) => (v ? self.channels.adopt(id, v) : self.channels.drop(id)),
      notify: () => self.dirty(),
    });
  }

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

  /** Geräte-Registrierung: Zertifikat des Konto-Schlüssels, Geräte-Inbox (aus dem AIK abgeleitet), KeyPackages. */
  private async deviceRegistration(client: Client) {
    const deviceId = client.deviceId();
    const inbox = JSON.parse(client.deviceInbox(deviceId)) as { mailbox_id: string; token: string; key: string };
    return {
      deviceId,
      device: { id: deviceId, dpk: b64(client.devicePublic()), cert: b64(client.deviceCert()) },
      inbox: { mailbox_id: inbox.mailbox_id, token_hash: b64(await sha256(enc.encode(inbox.token))) },
      inboxId: inbox.mailbox_id,
      inboxKey: b64(unhex(inbox.key)),
      keypackages: Array.from(client.keyPackages(KP_BATCH, false) as Uint8Array[]).map(b64),
      last_resort: b64((client.keyPackages(1, true) as Uint8Array[])[0]),
    };
  }

  private emptyState(me: AppState['me']): AppState {
    return {
      v: 1, me, backupDone: false, intro: null, conversations: {}, contacts: {}, mailboxes: {},
      blockedUsers: [], blockedServers: [], allowUsers: [], allowServers: [],
      filterMode: 'off', serverSideFilter: false, directSend: false, cursor: 0, outbox: [],
      sendDelivered: false, sendRead: false, onceDropOwnCopy: false,
    };
  }

  async createAccount(opts: { server: string; name: string; invite: string; passphrase: string }): Promise<void> {
    const core = this.core;
    const name = opts.name.trim().toLowerCase();
    const api0 = new Api(opts.server.trim().toLowerCase());
    const info = await api0.serverInfo();
    const domain = info.domain;
    if (info.registration === 'closed') throw new Error('Dieser Server nimmt keine Registrierungen an.');
    const client = new core.Client(`${name}@${domain}`);
    const ts = Math.floor(Date.now() / 1000);
    // Die Registrierung beweist den Besitz des Konto-Schlüssels (AIK).
    const sig = client.signAccount(enc.encode(`CHAT-REGISTER-V1\n${domain}\n${name}\n${ts}`));
    const pow = await solvePow(name, ts, info.pow_bits);
    const reg = await this.deviceRegistration(client);
    const res = await new Api(domain).register({
      invite: opts.invite.trim(), name, ik: b64(client.identityPublic()), ts, sig: b64(sig), pow,
      keypackages: reg.keypackages, last_resort: reg.last_resort, device: reg.device, inbox: reg.inbox,
    });
    const introKey = b64(core.envelopeKey());
    this.client = client;
    this.info = info;
    const st = this.emptyState({ address: `${name}@${domain}`, domain, name, deviceId: reg.deviceId, inboxId: reg.inboxId });
    st.knownDevices = [reg.deviceId]; // jedes später hinzukommende Gerät löst einen Hinweis aus
    st.intro = { mailbox_id: res.intro.mailbox_id, send_token: res.intro.send_token, key: introKey };
    st.mailboxes = { [res.intro.mailbox_id]: introKey, [reg.inboxId]: reg.inboxKey };
    this.state = st;
    this.vault = core.Vault.create(opts.passphrase);
    await this.persist();
    await kv.put('meta', { address: st.me.address });
    this.knownAddress = st.me.address;
    await this.start();
  }

  /**
   * Neues Gerät für ein bestehendes Konto: Backup-Datei (enthält den Konto-Schlüssel) → neuer Geräteschlüssel → Registrierung am Server.
   * Das Gerät startet ohne Gespräche; ein bereits aktives Gerät des Kontos nimmt es in die Gruppen auf (siehe docs/MULTIDEVICE.md).
   */
  async linkDevice(file: Uint8Array, backupPass: string, newPass: string): Promise<void> {
    const core = this.core;
    let plain: Uint8Array;
    try {
      plain = core.vaultOpen(backupPass, file);
    } catch {
      throw new Error('Falsche Passphrase oder beschädigte Backup-Datei.');
    }
    const j = JSON.parse(dec.decode(plain)) as { v: number; address: string; aik: string; sync: Partial<AppState> };
    if (j.v !== 2) throw new Error('Dieses Backup hat ein altes Format und kann nicht verwendet werden.');
    const parts = splitAddress(j.address);
    if (!parts) throw new Error('Ungültiges Backup.');
    const { name, domain } = parts;
    const client = core.Client.linkDevice(j.address, unb64(j.aik));
    const info = await new Api(domain).serverInfo();
    if (info.domain !== domain) throw new Error('Der Server meldet einen anderen Namen als im Backup.');
    const reg = await this.deviceRegistration(client);
    const ts = Math.floor(Date.now() / 1000);
    const sig = client.signAccount(enc.encode(`CHAT-ADD-DEVICE-V1\n${domain}\n${name}\n${ts}\n${reg.deviceId}`));
    await new Api(domain).addDevice({
      name, ts, sig: b64(sig), device: reg.device, inbox: reg.inbox, keypackages: reg.keypackages, last_resort: reg.last_resort,
    });
    const st = this.emptyState({ address: j.address, domain, name, deviceId: reg.deviceId, inboxId: reg.inboxId });
    const sync = j.sync ?? {};
    for (const k of ['contacts', 'blockedUsers', 'blockedServers', 'allowUsers', 'allowServers', 'filterMode', 'serverSideFilter', 'directSend', 'sendDelivered', 'sendRead', 'onceDropOwnCopy', 'intro', 'channels'] as const) {
      if (sync[k] !== undefined) (st as unknown as Record<string, unknown>)[k] = sync[k];
    }
    st.backupDone = true; // das Backup existiert ja bereits
    st.mailboxes = { [reg.inboxId]: reg.inboxKey };
    if (st.intro) st.mailboxes[st.intro.mailbox_id] = st.intro.key;
    this.client = client;
    this.info = info;
    this.state = st;
    this.vault = core.Vault.create(newPass);
    await this.persist();
    await kv.put('meta', { address: st.me.address });
    this.knownAddress = st.me.address;
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
    this.state.sendDelivered ??= false;
    this.state.sendRead ??= false;
    this.state.onceDropOwnCopy ??= false;
    this.state.channels ??= {};
  }

  /**
   * Backup-Datei: verschlüsselt (Argon2id) mit Konto-Schlüssel (AIK), Einstellungen und Kontakten. **Kein MLS-Zustand**
   * (deshalb kann ein Backup keinen Zustandsfork erzeugen); Verlauf und Gruppen kommen auf neuen Geräten per Aufnahme durch ein aktives Gerät.
   */
  exportBackup(passphrase: string): Uint8Array {
    const s = this.state!;
    const sync: Partial<AppState> = {
      contacts: s.contacts, blockedUsers: s.blockedUsers, blockedServers: s.blockedServers, allowUsers: s.allowUsers,
      allowServers: s.allowServers, filterMode: s.filterMode, serverSideFilter: s.serverSideFilter, directSend: s.directSend,
      sendDelivered: s.sendDelivered, sendRead: s.sendRead, onceDropOwnCopy: s.onceDropOwnCopy, intro: s.intro,
      channels: Object.fromEntries(Object.entries(s.channels ?? {}).map(([k, c]) => [k, { ...c, posts: [], events: [], cursor: 0, unread: 0 }])),
    };
    const json = JSON.stringify({ v: 2, address: s.me.address, aik: b64(this.client!.exportIdentity()), sync });
    return this.core.vaultSeal(passphrase, enc.encode(json));
  }

  /** Nach dem Speichern der Backup-Datei aufrufen (hebt die Pflicht nach der Registrierung auf). */
  markBackupDone(): void {
    this.state!.backupDone = true;
    this.dirty();
  }

  private serialize(): Uint8Array {
    return enc.encode(JSON.stringify({ mls: b64(this.client!.exportState()), app: this.state }));
  }

  private async persist(): Promise<void> {
    if (!this.vault || !this.client || !this.state) return;
    await kv.put('vault', this.vault.seal(this.serialize()));
  }

  /** Zustand speichern (entprellt). */
  dirty() {
    this.emit();
    this.scheduleSync();
    if (this.saveTimer) return;
    this.saveTimer = setTimeout(() => {
      this.saveTimer = null;
      void this.persist();
    }, 250);
  }

  /** Änderungen an synchronisierten Daten (Kanäle, Einstellungen, Blocklisten, Kontakte) entprellt an die anderen Geräte weitergeben. */
  private scheduleSync(): void {
    if (this.syncTimer || !this.state || !this.api || this.closed) return;
    this.syncTimer = setTimeout(() => {
      this.syncTimer = null;
      if (!this.state || this.accountSync.signature() === this.lastSyncSig) return;
      this.enqueue(() => this.syncNow());
    }, 1500);
  }

  private async syncNow(): Promise<void> {
    if (!this.state || !this.api) return;
    try {
      await this.accountSync.run();
    } catch (e) {
      console.warn('sync', e);
    }
    if (this.state) this.lastSyncSig = this.accountSync.signature();
  }

  private async flush(): Promise<void> {
    if (this.saveTimer) {
      clearTimeout(this.saveTimer);
      this.saveTimer = null;
    }
    await this.persist();
  }

  async lock(): Promise<void> {
    void clearSession(); // explizites Sperren (und Inaktivitäts-Sperre) beendet „Angemeldet bleiben“
    await this.flush();
    this.closed = true;
    this.channels.stop();
    if (this.syncTimer) clearTimeout(this.syncTimer);
    this.syncTimer = null;
    this.mediaCache.forEach((u) => void u.then((x) => URL.revokeObjectURL(x), () => undefined));
    this.mediaCache.clear();
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
    this.api.call<{ admin: boolean }>('GET', '/v1/me').then((m) => { this.isAdmin = m.admin; this.emit(); }, () => undefined);
    this.connect();
    this.channels.start();
    this.timers.push(setInterval(() => this.purgeExpired(), 30_000));
    this.timers.push(setInterval(() => void this.retryOutbox(), 30_000));
    this.timers.push(setInterval(() => this.enqueue(() => this.syncNow()), 60_000)); // Sicherheitsnetz, falls ein Sync-Ereignis verpasst wurde
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
        this.enqueue(() => this.reconcileDevices());
        this.enqueue(() => this.syncNow());
        void this.retryOutbox();
        void this.replenishKeyPackages();
      } else if (m.type === 'sync') {
        this.enqueue(() => this.syncNow());
      } else if (m.type === 'devices') {
        this.enqueue(() => this.reconcileDevices());
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
    let opened: [number, Uint8Array, Uint8Array, Uint8Array];
    try {
      opened = this.core.envelopeOpen(unb64(key), data) as [number, Uint8Array, Uint8Array, Uint8Array];
    } catch {
      return;
    }
    const [kind, gid, dev, payload] = opened;
    if (dec.decode(dev) === s.me.deviceId) return; // eigene Kopie (Zustellung an alle Geräte des Kontos)
    if (kind === KIND_WELCOME) await this.handleWelcome(payload, mailboxId === s.me.inboxId);
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
        this.addAlert({ id: `key-${m.address}-${m.ik.slice(0, 8)}`, kind: 'key', text: conv.warning });
        c.verified = false;
      }
    }
  }

  /** Ein Eintrag je Gerät (MLS-Blatt). */
  private readMembers(gid: Uint8Array): Conversation['members'] {
    return (JSON.parse(this.client!.members(gid)) as { address: string; identity: string; device: string }[]).map((m) => ({
      address: m.address,
      ik: m.identity,
      device: m.device,
    }));
  }

  private memberAddresses(conv: Conversation): string[] {
    return Array.from(new Set(conv.members.map((m) => m.address)));
  }

  /**
   * `viaInbox`: das Welcome kam in der Geräte-Inbox an, also von einem anderen Gerät des eigenen Kontos
   * (Gerät wurde hinzugefügt): kein Anfrage-Dialog, sofort aktiv.
   */
  private async handleWelcome(welcome: Uint8Array, viaInbox: boolean): Promise<void> {
    const s = this.state!;
    const gid = this.client!.join(welcome);
    const id = hex(gid);
    if (s.conversations[id]) return;
    const members = this.readMembers(gid);
    const addrs = Array.from(new Set(members.map((m) => m.address)));
    const others = addrs.filter((a) => a !== s.me.address);
    // Blockierte oder nicht erlaubte Absender: stillschweigend verwerfen (der Absender erfährt nichts).
    if (!viaInbox && (others.some((a) => s.blockedUsers.includes(a) || s.blockedServers.includes(splitAddress(a)?.domain ?? '')) ||
        (s.filterMode === 'allow' && !others.some((a) => !this.isBlocked(a))))) {
      this.client!.deleteGroup(gid);
      return;
    }
    if (others.length === 0) { // nur eigene Geräte: kein Gespräch
      this.client!.deleteGroup(gid);
      return;
    }
    const kind = addrs.length === 2 ? 'dm' : 'group';
    const conv: Conversation = {
      id, kind, title: kind === 'dm' ? others[0] : 'Gruppe', status: viaInbox ? 'active' : 'request', members,
      caps: {}, messages: [], unread: viaInbox ? 0 : 1, disappearSeconds: 0, createdAt: Date.now(),
    };
    s.conversations[id] = conv;
    this.pinMembers(conv);
    if (viaInbox) {
      conv.pendingAnnounce = true; // erst ankündigen, wenn wir über das Verzeichnis die Postfächer der anderen kennen
      return;
    }
    if (kind === 'dm' && s.contacts[others[0]]?.verified) await this.acceptRequestLocked(id);
  }

  private async handleMls(id: string, gid: Uint8Array, payload: Uint8Array): Promise<void> {
    const s = this.state!;
    const conv = s.conversations[id];
    if (!conv || conv.status === 'left') return;
    const res = JSON.parse(this.client!.process(gid, payload)) as
      | { kind: 'application'; sender: string; senderDevice: string; envelope: Envelope }
      | { kind: 'commit'; sender: string; removedSelf: boolean }
      | { kind: 'proposal' };
    if (res.kind === 'commit') {
      if (res.removedSelf) {
        conv.status = 'left';
        this.client!.deleteGroup(gid);
      } else {
        conv.members = this.readMembers(gid);
        this.pinMembers(conv);
        this.rebuildCaps(conv);
      }
      return;
    }
    if (res.kind !== 'application') return;
    // Zustand ist fortgeschrieben; blockierte Absender werden erst jetzt verworfen (eigene Geräte nie).
    if (res.sender !== s.me.address && this.isBlocked(res.sender)) return;
    this.applyContent(conv, res.sender, res.senderDevice, res.envelope);
    await this.flushRelays();
    await this.flushReceipts();
    if (conv.pendingAnnounce && conv.status === 'active' && Object.keys(conv.caps).length > 0) {
      conv.pendingAnnounce = false;
      await this.ensureMailbox(conv);
      await this.announce(conv);
    }
  }

  private async flushReceipts(): Promise<void> {
    const s = this.state!;
    const list = this.receipts;
    this.receipts = [];
    for (const { convId, kind, ids } of list) {
      const conv = s.conversations[convId];
      if (!conv || conv.status !== 'active' || conv.kind !== 'dm') continue;
      await this.broadcast(conv, this.newEnvelope({ kind: 'receipt', receipt: kind, references: ids }));
    }
  }

  /** Postfach-Verzeichnis vervollständigen: neue Selbst-Ankündigungen an die übrigen Mitglieder weiterreichen, dem Neuen die bekannten schicken. */
  private async flushRelays(): Promise<void> {
    const s = this.state!;
    const list = this.relays;
    this.relays = [];
    for (const { convId, entry, sender } of list) {
      const conv = s.conversations[convId];
      if (!conv || conv.status !== 'active') continue;
      const senderKey = `${sender}#${entry.device}`;
      const others = conv.members
        .filter((m) => !(m.address === s.me.address && m.device === s.me.deviceId) && `${m.address}#${m.device}` !== senderKey)
        .map((m) => `${m.address}#${m.device}`);
      if (others.length) await this.sendContent(conv, { kind: 'directory', entries: [entry] }, others);
      await this.sendContent(conv, { kind: 'directory', entries: this.knownEntries(conv, senderKey) }, [senderKey]);
    }
  }

  /** Alle bekannten Geräte-Postfächer der Unterhaltung (ohne Intro-Platzhalter und ohne `exceptKey`). */
  private knownEntries(conv: Conversation, exceptKey?: string): CapEntry[] {
    return Object.entries(conv.caps)
      .filter(([k, c]) => k !== exceptKey && !c.intro && c.device)
      .map(([k, c]) => ({ address: k.split('#')[0], device: c.device!, domain: c.domain, mailbox_id: c.mailbox_id, send_token: c.send_token, key: c.key }));
  }

  private async sendContent(conv: Conversation, content: Content, only: string[]): Promise<void> {
    const gid = unhex(conv.id);
    const ct = this.client!.encryptEnvelope(gid, JSON.stringify(this.newEnvelope(content)));
    await this.sendCt(conv, gid, KIND_MLS, ct, only);
  }

  /** Postfächer von Geräten entfernen, die nicht mehr Mitglied sind. */
  private rebuildCaps(conv: Conversation) {
    const leaves = new Set(conv.members.map((m) => `${m.address}#${m.device}`));
    const addrs = new Set(conv.members.map((m) => m.address));
    for (const k of Object.keys(conv.caps)) {
      if (k.includes('#') ? !leaves.has(k) : !addrs.has(k)) delete conv.caps[k];
    }
  }

  private applyContent(conv: Conversation, sender: string, senderDevice: string, env: Envelope): void {
    const s = this.state!;
    const c = env.content;
    // Absender muss aktuelles Mitglied sein (MLS garantiert das, wir prüfen zusätzlich die Adresse).
    if (!conv.members.some((m) => m.address === sender)) return;
    switch (c.kind) {
      case 'message': {
        if (conv.messages.some((m) => m.id === env.id)) return;
        const once = c.once === true && conv.kind === 'dm'; // Einmal-Nachrichten gibt es nur in 1:1-Chats
        const own = sender === s.me.address; // von einem anderen eigenen Gerät
        const msg: Msg = {
          id: env.id, from: sender, ts: Math.min(env.ts, Date.now() + 5 * 60_000), parts: c.parts,
          status: own ? 'sent' : 'received', reactions: {}, ...(once ? { once: true } : {}),
        };
        if (conv.disappearSeconds > 0) msg.expiresAt = Date.now() + conv.disappearSeconds * 1000;
        conv.messages.push(msg);
        conv.messages.sort((a, b) => a.ts - b.ts);
        if (!own) {
          conv.unread++;
          if (conv.status === 'active') playNotify();
          if (conv.kind === 'dm' && s.sendDelivered) this.receipts.push({ convId: conv.id, kind: 'delivered', ids: [env.id] });
        }
        break;
      }
      case 'receipt': {
        if (conv.kind !== 'dm' || sender === s.me.address) break;
        const rank = { sending: 0, failed: 0, received: 0, sent: 1, delivered: 2, read: 3 } as const;
        for (const ref of c.references.slice(0, 200)) {
          const m = conv.messages.find((x) => x.id === ref && x.from === s.me.address);
          if (m && rank[c.receipt] > rank[m.status]) m.status = c.receipt;
        }
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
          const key = `${e.address}#${e.device}`;
          const before = conv.caps[key]?.mailbox_id;
          this.mergeCap(conv, sender, senderDevice, e);
          const after = conv.caps[key]?.mailbox_id;
          // Neue Selbst-Ankündigung eines Geräts: an die übrigen Mitglieder weiterreichen (sie kennen sein Postfach evtl. noch nicht).
          if (e.address === sender && e.device === senderDevice && after && after !== before && conv.status === 'active') {
            this.relays.push({ convId: conv.id, entry: e, sender });
          }
        }
        break;
    }
  }

  /** Ein Gerät darf nur sein eigenes Postfach überschreiben; für andere Geräte gilt „first write wins“ (kein Umleiten durch Dritte). */
  private mergeCap(conv: Conversation, sender: string, senderDevice: string, e: CapEntry): void {
    const s = this.state!;
    if (!splitAddress(e.address) || !e.device || !e.domain || !e.mailbox_id || !e.send_token || !e.key) return;
    if (e.address === s.me.address && e.device === s.me.deviceId) return; // wir selbst
    const key = `${e.address}#${e.device}`;
    const isSelf = e.address === sender && e.device === senderDevice;
    if (!isSelf && conv.caps[key]) return;
    if (!isSelf && !conv.members.some((m) => m.address === e.address && m.device === e.device)) return;
    conv.caps[key] = { domain: e.domain, mailbox_id: e.mailbox_id, send_token: e.send_token, key: e.key, device: e.device };
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

  /**
   * Postfächer für den Versand: je Mitglieds-Gerät das angekündigte Postfach; kennen wir von einem Konto noch kein Geräte-Postfach,
   * dient das Intro-Postfach (kontoweit) als Ersatz. `only`: Einträge `adresse` (alle Geräte) oder `adresse#gerät`.
   */
  private capsForSend(conv: Conversation, only?: string[]): Cap[] {
    const s = this.state!;
    const out: Cap[] = [];
    for (const addr of this.memberAddresses(conv)) {
      const leaves = conv.members.filter((m) => m.address === addr && !(addr === s.me.address && m.device === s.me.deviceId));
      if (leaves.length === 0) continue;
      const caps: Cap[] = [];
      for (const l of leaves) {
        const key = `${addr}#${l.device}`;
        if (only && !only.includes(addr) && !only.includes(key)) continue;
        const c = conv.caps[key];
        if (c) caps.push(c);
      }
      if (caps.length === 0 && addr !== s.me.address && (!only || only.includes(addr))) {
        const fallback = conv.caps[addr];
        if (fallback) caps.push(fallback);
      }
      out.push(...caps);
    }
    return out;
  }

  private async sendCt(conv: Conversation, gid: Uint8Array, kind: number, ct: Uint8Array, only?: string[]): Promise<number> {
    const dev = enc.encode(this.state!.me.deviceId);
    let sent = 0;
    for (const cap of this.capsForSend(conv, only)) {
      const blob = this.core.envelopeSeal(unb64(cap.key), kind, gid, dev, ct);
      await this.deliver(cap, blob);
      sent++;
    }
    return sent;
  }

  /** `account`: kontoweit (Zustellung an alle Geräte), sonst nur für dieses Gerät. */
  private async newMailbox(account = false): Promise<{ id: string; key: string; token: string }> {
    const r = await this.api!.call<{ mailbox_id: string; send_token: string }>('POST', '/v1/mailboxes', account ? { scope: 'account' } : undefined);
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
      address: s.me.address, device: s.me.deviceId, domain: s.me.domain, mailbox_id: mb.id, send_token: mb.token, key: mb.key,
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
      const mb = await this.newMailbox(true);
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
    const { kps, ik } = await this.fetchKeyPackages(addr);
    const contact = (s.contacts[addr] ??= { address: addr, ik, verified: false });
    if (contact.ik !== ik) throw new Error('Der Schlüssel dieses Kontos hat sich geändert. Bitte neu verifizieren.');
    contact.intro = card.cap;
    const gid = this.client!.createGroup();
    // Alle Geräte des Kontakts werden aufgenommen; das Welcome geht an das kontoweite Intro-Postfach (alle Geräte erhalten es).
    const [, welcome] = this.client!.addMembers(gid, kps.map((k) => k.kp)) as Uint8Array[];
    const id = hex(gid);
    const conv: Conversation = {
      id, kind: 'dm', title: addr, status: 'active', members: this.readMembers(gid),
      caps: { [addr]: card.cap }, messages: [], unread: 0, disappearSeconds: 0, createdAt: Date.now(),
    };
    s.conversations[id] = conv;
    await this.ensureMailbox(conv);
    await this.flush();
    await this.deliver(card.cap, this.core.envelopeSeal(unb64(card.cap.key), KIND_WELCOME, new Uint8Array(), enc.encode(s.me.deviceId), welcome));
    await this.announce(conv);
    this.dirty();
    return id;
  }

  /** KeyPackages aller (oder der genannten) Geräte eines Kontos; prüft Adresse, Konto-Schlüssel und Gerätezertifikat. */
  private async fetchKeyPackages(addr: string, devices?: string[]): Promise<{ kps: { device: string; kp: Uint8Array }[]; ik: string }> {
    const q = devices?.length ? '?' + devices.map((d) => `device=${d}`).join('&') : '';
    const r = await this.api!.call<{ ik: string; devices: { device: string; keypackage: string }[] }>(
      'GET', `/v1/resolve/${encodeURIComponent(addr)}/keypackages${q}`);
    const ik = hex(unb64(r.ik));
    const kps = r.devices.map((d) => {
      const kp = unb64(d.keypackage);
      let id: { address: string; identity: string; device: string };
      try {
        id = JSON.parse(this.client!.keyPackageIdentity(kp));
      } catch {
        throw new Error('Ungültiges Gerätezertifikat (möglicher Manipulationsversuch).');
      }
      if (id.address !== addr) throw new Error('Der Server hat ein KeyPackage für eine andere Adresse geliefert.');
      if (id.identity !== ik) throw new Error('Schlüssel stimmt nicht überein (möglicher Manipulationsversuch).');
      if (id.device !== d.device) throw new Error('Gerätekennung stimmt nicht überein.');
      return { device: d.device, kp };
    });
    if (kps.length === 0) throw new Error('Kein Gerät dieses Kontos ist erreichbar.');
    return { kps, ik };
  }

  private async acceptRequestLocked(id: string): Promise<void> {
    const conv = this.state!.conversations[id];
    if (!conv || conv.status !== 'request') return;
    conv.status = 'active';
    await this.ensureMailbox(conv);
    await this.announce(conv);
    this.dirty();
  }

  async acceptRequest(id: string): Promise<void> {
    await this.acceptRequestLocked(id);
  }

  async declineRequest(id: string): Promise<void> {
    const conv = this.state!.conversations[id];
    if (!conv) return;
    this.client!.deleteGroup(unhex(id));
    delete this.state!.conversations[id];
    this.dirty();
  }

  /** Bekannte Postfächer eines Kontos: Geräte-Postfächer aus aktiven 1:1-Chats, sonst das Intro-Postfach. */
  private knownCaps(addr: string): Record<string, Cap> {
    const s = this.state!;
    const out: Record<string, Cap> = {};
    for (const c of Object.values(s.conversations)) {
      if (c.kind !== 'dm' || c.status !== 'active') continue;
      for (const [k, cap] of Object.entries(c.caps)) if (k.startsWith(addr + '#') && !cap.intro) out[k] = cap;
    }
    if (Object.keys(out).length === 0) {
      const intro = s.contacts[addr]?.intro ?? Object.values(s.conversations).map((c) => c.caps[addr]).find(Boolean);
      if (intro) out[addr] = intro;
    }
    return out;
  }

  /** Welcome an alle Geräte eines Mitglieds: Geräte-Postfächer plus (falls nicht jedes Gerät eines hat) das kontoweite Intro-Postfach. */
  private async sendWelcomeTo(conv: Conversation, gid: Uint8Array, welcome: Uint8Array, addr: string): Promise<void> {
    const s = this.state!;
    const dev = enc.encode(s.me.deviceId);
    const leaves = conv.members.filter((m) => m.address === addr);
    const caps: Cap[] = leaves.map((l) => conv.caps[`${addr}#${l.device}`]).filter((c): c is Cap => !!c);
    const intro = s.contacts[addr]?.intro ?? conv.caps[addr];
    if (intro && (caps.length < leaves.length || caps.length === 0)) caps.push(intro);
    for (const cap of caps) await this.deliver(cap, this.core.envelopeSeal(unb64(cap.key), KIND_WELCOME, gid, dev, welcome));
  }

  /** Gruppe mit bekannten Kontakten anlegen. */
  async createGroup(title: string, addresses: string[]): Promise<string> {
    const s = this.state!;
    if (addresses.length === 0) throw new Error('Mindestens ein Mitglied wählen.');
    const kps: Uint8Array[] = [];
    const caps: Record<string, Cap> = {};
    for (const a of addresses) {
      const known = this.knownCaps(a);
      if (Object.keys(known).length === 0) throw new Error(`${a} ist kein bekannter Kontakt.`);
      const r = await this.fetchKeyPackages(a);
      const c = (s.contacts[a] ??= { address: a, ik: r.ik, verified: false });
      if (c.ik !== r.ik) throw new Error(`Schlüssel von ${a} hat sich geändert.`);
      kps.push(...r.kps.map((k) => k.kp));
      Object.assign(caps, known);
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
    for (const a of addresses) await this.sendWelcomeTo(conv, gid, welcome, a);
    await this.announce(conv);
    await this.broadcast(conv, this.newEnvelope({ kind: 'group_name', name: conv.title }));
    this.dirty();
    return id;
  }

  async addMember(id: string, address: string): Promise<void> {
    const s = this.state!;
    const conv = s.conversations[id];
    if (!conv || conv.kind !== 'group') throw new Error('Keine Gruppe.');
    if (conv.members.some((m) => m.address === address)) throw new Error('Bereits Mitglied.');
    const known = this.knownCaps(address);
    if (Object.keys(known).length === 0) throw new Error(`${address} ist kein bekannter Kontakt.`);
    const r = await this.fetchKeyPackages(address);
    const c = (s.contacts[address] ??= { address, ik: r.ik, verified: false });
    if (c.ik !== r.ik) throw new Error('Schlüssel hat sich geändert.');
    const gid = unhex(id);
    const before = this.memberAddresses(conv);
    const [commit, welcome] = this.client!.addMembers(gid, r.kps.map((k) => k.kp)) as Uint8Array[];
    conv.members = this.readMembers(gid);
    // Commit an bisherige Mitglieder, Welcome + Verzeichnis an das neue.
    await this.sendCt(conv, gid, KIND_MLS, commit, before);
    Object.assign(conv.caps, known);
    await this.sendWelcomeTo(conv, gid, welcome, address);
    await this.announce(conv, this.knownEntries(conv), [address]);
    await this.broadcast(conv, this.newEnvelope({ kind: 'group_name', name: conv.title }));
    this.dirty();
  }

  async removeMember(id: string, address: string): Promise<void> {
    const s = this.state!;
    const conv = s.conversations[id];
    if (!conv || conv.kind !== 'group') return;
    const gid = unhex(id);
    const commit = this.client!.removeMembers(gid, [address]);
    // Auch an die entfernten Geräte senden (sie erfahren so von der Entfernung); erst danach Mitglieder/Postfächer aktualisieren.
    await this.sendCt(conv, gid, KIND_MLS, commit);
    conv.members = this.readMembers(gid);
    this.rebuildCaps(conv);
    void s;
    this.dirty();
  }

  // ---------- Geräte (Multi-Device) ----------

  async listDevices(): Promise<DeviceInfo[]> {
    return this.api!.call<DeviceInfo[]>('GET', '/v1/devices');
  }

  /** Gerät widerrufen: es verliert Anmeldung und Postfach; ein aktives Gerät entfernt es aus allen Gruppen. */
  async revokeDevice(id: string): Promise<void> {
    await this.api!.call('DELETE', `/v1/devices/${id}`);
    this.enqueue(() => this.reconcileDevices());
  }

  private inboxCap(deviceId: string): Cap {
    const s = this.state!;
    const i = JSON.parse(this.client!.deviceInbox(deviceId)) as { mailbox_id: string; token: string; key: string };
    return { domain: s.me.domain, mailbox_id: i.mailbox_id, send_token: i.token, key: b64(unhex(i.key)), device: deviceId };
  }

  /**
   * Abgleich der Geräte des eigenen Kontos mit den Blättern in jeder Gruppe: Das Gerät mit der kleinsten ID unter den bereits
   * beteiligten nimmt neue Geräte auf und entfernt widerrufene (siehe docs/MULTIDEVICE.md).
   */
  private async reconcileDevices(): Promise<void> {
    const s = this.state;
    if (!s || !this.api || this.reconciling) return;
    this.reconciling = true;
    try {
      const devs = await this.listDevices();
      this.trackDevices(devs);
      const active = new Set(devs.map((d) => d.id));
      for (const conv of Object.values(s.conversations)) {
        if (conv.status !== 'active') continue;
        const own = conv.members.filter((m) => m.address === s.me.address);
        const present = own.filter((m) => active.has(m.device)).map((m) => m.device).sort();
        if (present[0] !== s.me.deviceId) continue; // nicht unser Zug
        const stale = own.filter((m) => !active.has(m.device)).map((m) => m.device);
        const missing = devs.map((d) => d.id).filter((id) => !own.some((m) => m.device === id));
        try {
          if (stale.length) await this.removeOwnDevices(conv, stale);
          if (missing.length) await this.addOwnDevices(conv, missing);
        } catch (e) {
          console.warn('reconcile', conv.id, e);
        }
      }
      await this.flush();
    } finally {
      this.reconciling = false;
    }
  }

  private async removeOwnDevices(conv: Conversation, stale: string[]): Promise<void> {
    const s = this.state!;
    const gid = unhex(conv.id);
    const commit = this.client!.removeDevices(gid, JSON.stringify(stale.map((device) => ({ address: s.me.address, device }))));
    conv.members = this.readMembers(gid);
    this.rebuildCaps(conv);
    await this.sendCt(conv, gid, KIND_MLS, commit);
    this.dirty();
  }

  private async addOwnDevices(conv: Conversation, deviceIds: string[]): Promise<void> {
    const s = this.state!;
    const { kps, ik } = await this.fetchKeyPackages(s.me.address, deviceIds);
    if (ik !== hex(this.client!.identityPublic())) throw new Error('Konto-Schlüssel stimmt nicht überein.');
    const gid = unhex(conv.id);
    const [commit, welcome] = this.client!.addMembers(gid, kps.map((k) => k.kp)) as Uint8Array[];
    conv.members = this.readMembers(gid);
    await this.ensureMailbox(conv);
    await this.sendCt(conv, gid, KIND_MLS, commit); // an alle Geräte mit bekanntem Postfach (die neuen noch nicht)
    const me = enc.encode(s.me.deviceId);
    const mb = conv.myMailbox!;
    const own: CapEntry = { address: s.me.address, device: s.me.deviceId, domain: s.me.domain, mailbox_id: mb.id, send_token: mb.token, key: mb.key };
    const entries = [own, ...this.knownEntries(conv)];
    for (const { device } of kps) {
      // Welcome und Verzeichnis gehen an die Geräte-Inbox (aus dem Konto-Schlüssel abgeleitet, der Server sieht keine Geheimnisse).
      const cap = this.inboxCap(device);
      const key = unb64(cap.key);
      await this.deliver(cap, this.core.envelopeSeal(key, KIND_WELCOME, new Uint8Array(), me, welcome));
      const send = async (content: Content) => {
        const ct = this.client!.encryptEnvelope(gid, JSON.stringify(this.newEnvelope(content)));
        await this.deliver(cap, this.core.envelopeSeal(key, KIND_MLS, gid, me, ct));
      };
      await send({ kind: 'directory', entries });
      if (conv.kind === 'group') await send({ kind: 'group_name', name: conv.title });
    }
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
    o: { text?: string; code?: { lang: string; body: string }; quote?: Msg; files?: File[]; once?: boolean },
  ): Promise<void> {
    const s = this.state!;
    const conv = s.conversations[id];
    if (!conv || conv.status !== 'active') throw new Error('Unterhaltung nicht aktiv.');
    if (o.once && conv.kind !== 'dm') throw new Error('Einmal-Nachrichten gibt es nur in privaten 1:1-Chats.');
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
    const env = this.newEnvelope({ kind: 'message', parts, ...(o.once ? { once: true } : {}) });
    const msg: Msg = { id: env.id, from: s.me.address, ts: env.ts, parts, status: 'sending', reactions: {}, ...(o.once ? { once: true } : {}) };
    if (conv.disappearSeconds > 0) msg.expiresAt = Date.now() + conv.disappearSeconds * 1000;
    conv.messages.push(msg);
    this.dirty();
    const n = await this.broadcast(conv, env);
    // Ein Empfangsstatus (delivered/read) kann bereits eingetroffen sein, während wir noch auf den Server warteten.
    if (msg.status === 'sending') msg.status = n > 0 ? 'sent' : 'failed';
    if (o.once && s.onceDropOwnCopy) {
      msg.parts = [];
      msg.consumed = true;
    }
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
    return new Blob([data as BlobPart], { type: isSafeMedia(p.mime) ? p.mime : 'application/octet-stream' });
  }

  private mediaCache = new Map<string, Promise<string>>();

  /** Entschlüsselte Datei als Blob-URL (für Bilder/Audio/Video); je Datei nur einmal geladen. */
  mediaUrl(p: Extract<Part, { type: 'file' }>): Promise<string> {
    let r = this.mediaCache.get(p.blob_id);
    if (!r) {
      r = this.downloadFile(p).then((b) => URL.createObjectURL(b));
      r.catch(() => this.mediaCache.delete(p.blob_id));
      this.mediaCache.set(p.blob_id, r);
    }
    return r;
  }

  dismissConvWarning(id: string): void {
    const c = this.state!.conversations[id];
    if (c) delete c.warning;
    this.dirty();
  }

  dismissAlert(id: string): void {
    const s = this.state!;
    s.alerts = (s.alerts ?? []).filter((a) => a.id !== id);
    this.dirty();
  }

  private addAlert(a: Omit<SecurityAlert, 'ts'>): void {
    const s = this.state!;
    s.alerts ??= [];
    if (s.alerts.some((x) => x.id === a.id)) return;
    s.alerts.push({ ...a, ts: Date.now() });
    this.dirty();
  }

  /** Erkennt Geräte, die seit dem letzten Abgleich neu zum Konto hinzugekommen sind. */
  private trackDevices(devs: DeviceInfo[]): void {
    const s = this.state!;
    const ids = devs.map((d) => d.id);
    if (!s.knownDevices) {
      s.knownDevices = ids;
      return;
    }
    for (const id of ids) {
      if (s.knownDevices.includes(id)) continue;
      s.knownDevices.push(id);
      this.addAlert({ id: `dev-${id}`, kind: 'device', text: `Neues Gerät ${id} wurde deinem Konto hinzugefügt. Warst du das nicht, widerrufe es sofort (Einstellungen → Geräte).` });
    }
    s.knownDevices = s.knownDevices.filter((id) => ids.includes(id));
  }

  async react(id: string, ref: string, emoji: string): Promise<void> {
    const conv = this.state!.conversations[id];
    const m = conv?.messages.find((x) => x.id === ref);
    if (!conv || !m) return;
    const env = this.newEnvelope({ kind: 'reaction', reference: ref, emoji });
    this.applyContent(conv, this.state!.me.address, this.state!.me.deviceId, env);
    await this.broadcast(conv, env);
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

  /** Gruppe umbenennen (alle Mitglieder erhalten den neuen Namen). */
  async renameGroup(id: string, name: string): Promise<void> {
    const conv = this.state!.conversations[id];
    if (!conv || conv.kind !== 'group' || conv.status !== 'active') throw new Error('Nur aktive Gruppen lassen sich umbenennen.');
    const n = name.trim().slice(0, 80);
    if (!n) throw new Error('Der Name darf nicht leer sein.');
    conv.title = n;
    await this.broadcast(conv, this.newEnvelope({ kind: 'group_name', name: n }));
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
    const s = this.state;
    const conv = s?.conversations[id];
    if (!s || !conv) return;
    let changed = false;
    if (conv.unread) {
      conv.unread = 0;
      changed = true;
    }
    // Lesebestätigung (nur 1:1, nur wenn eingeschaltet). Einmal-Nachrichten bestätigen erst beim Anzeigen.
    if (s.sendRead && conv.kind === 'dm' && conv.status === 'active') {
      const ids = conv.messages.filter((m) => m.from !== s.me.address && !m.once && !m.readAck && !m.deleted).map((m) => m.id);
      if (ids.length) {
        for (const m of conv.messages) if (ids.includes(m.id)) m.readAck = true;
        this.receipts.push({ convId: id, kind: 'read', ids });
        this.enqueue(() => this.flushReceipts());
        changed = true;
      }
    }
    if (changed) this.dirty();
  }

  /**
   * Einmal-Nachricht anzeigen: liefert den Inhalt genau einmal zurück, löscht ihn lokal sofort und meldet dem Absender „gelesen“
   * (auch wenn Lesebestätigungen sonst ausgeschaltet sind – das Anzeigen ist hier der Zweck der Nachricht).
   */
  revealOnce(convId: string, msgId: string): Part[] | null {
    const s = this.state!;
    const conv = s.conversations[convId];
    const m = conv?.messages.find((x) => x.id === msgId);
    if (!conv || !m || !m.once || m.consumed || m.from === s.me.address) return null;
    const parts = m.parts;
    m.parts = [];
    m.consumed = true;
    m.readAck = true;
    this.receipts.push({ convId, kind: 'read', ids: [msgId] });
    this.enqueue(() => this.flushReceipts());
    this.dirty();
    return parts;
  }

  setReceiptSettings(o: { sendDelivered?: boolean; sendRead?: boolean; onceDropOwnCopy?: boolean }): void {
    const s = this.state!;
    if (o.sendDelivered !== undefined) s.sendDelivered = o.sendDelivered;
    if (o.sendRead !== undefined) s.sendRead = o.sendRead;
    if (o.onceDropOwnCopy !== undefined) s.onceDropOwnCopy = o.onceDropOwnCopy;
    this.dirty();
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

  // ---------- Administration (nur für Administratoren) ----------

  adminStats() {
    return this.api!.call<{ stats: Record<string, number>; uptime_seconds: number; requests_total: number; ws_connections: number; goroutines: number; memory_bytes: number; domain: string }>('GET', '/v1/admin/stats');
  }
  adminSettings() {
    return this.api!.call<Record<string, unknown>>('GET', '/v1/admin/settings');
  }
  saveAdminSettings(st: Record<string, unknown>) {
    return this.api!.call<Record<string, unknown>>('PUT', '/v1/admin/settings', st);
  }
  adminUsers() {
    return this.api!.call<{ users: { name: string; admin: boolean; created_at: number; devices: number; blob_bytes: number; channels: number }[] }>('GET', '/v1/admin/users');
  }
  setAdmin(name: string, admin: boolean) {
    return this.api!.call('PUT', `/v1/admin/users/${encodeURIComponent(name)}/admin`, { admin });
  }

  async createInvite(): Promise<string> {
    return (await this.api!.call<{ invite: string }>('POST', '/v1/invites', {})).invite;
  }

  get lastSeqSeen() {
    return this.lastSeq;
  }
}

/** Dateitypen, die der Browser direkt anzeigen/abspielen darf (alles andere wird nur als Download angeboten). */
export const IMAGE_RE = /^image\/(png|jpeg|gif|webp)$/;
export const AUDIO_RE = /^audio\/(mpeg|mp3|ogg|wav|x-wav|webm|aac|flac|mp4|x-m4a)$/;
export const VIDEO_RE = /^video\/(mp4|webm|ogg|quicktime)$/;
export const isSafeMedia = (mime: string) => IMAGE_RE.test(mime) || AUDIO_RE.test(mime) || VIDEO_RE.test(mime);

export function snippetOf(m: Msg): string {
  if (m.once) return '🔒 Einmal-Nachricht'; // nie Inhalt einer Einmal-Nachricht in Vorschau/Zitat
  for (const p of m.parts) {
    if (p.type === 'text') return p.body;
    if (p.type === 'code') return p.body;
    if (p.type === 'file') return `📎 ${p.name}`;
  }
  return m.deleted ? 'Nachricht gelöscht' : '';
}

export type { Contact };

/** Adressen der Mitglieder (ein Konto kann mehrere Geräte/Blätter haben). */
export function memberAddresses(conv: Conversation): string[] {
  return Array.from(new Set(conv.members.map((m) => m.address)));
}
