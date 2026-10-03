import type { Client } from './core';
import type { ServerInfo } from './types';
import { b64, baseUrl, enc, hex, randomBytes, sha256, unb64 } from './util';

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

/** fetch mit verständlicher Fehlermeldung (der Browser liefert bei Netzwerkfehlern nur „Failed to fetch“). */
async function doFetch(url: string, init?: RequestInit): Promise<Response> {
  try {
    return await fetch(url, init);
  } catch (e) {
    console.error('fetch failed', url, e);
    const origin = new URL(url).origin;
    let hint = 'Server nicht erreichbar, Firewall/Port blockiert oder die Adresse ist falsch.';
    if (location.protocol === 'https:' && url.startsWith('http:')) {
      hint = 'Die Seite wurde über https geladen, der Server spricht aber nur http (Mixed Content). Öffne die App über http://IP:PORT.';
    } else if (location.origin !== origin) {
      hint += ' Bei fremden Servern muss dieser CORS erlauben und erreichbar sein.';
    }
    throw new Error(`Verbindung zu ${origin} fehlgeschlagen. ${hint}`);
  }
}

export interface Signer {
  name: string;
  /** Geräte-ID (Anfragen werden mit dem Geräteschlüssel signiert). */
  deviceId: string;
  sign(data: Uint8Array): Uint8Array;
}

/** Signierter API-Client für den Home-Server (siehe docs/PROTOCOL.md §2). */
export class Api {
  readonly base: string;
  constructor(public domain: string, private signer?: Signer) {
    this.base = baseUrl(domain);
  }

  static fromClient(domain: string, name: string, c: Client): Api {
    return new Api(domain, { name, deviceId: c.deviceId(), sign: (d) => c.sign(d) });
  }

  private async authHeader(method: string, uri: string, bodyHash: string): Promise<string> {
    if (!this.signer) throw new Error('not authenticated');
    const ts = Math.floor(Date.now() / 1000).toString();
    const nonce = b64(randomBytes(12));
    const msg = `CHAT-REQ-V1\n${this.domain}\n${method}\n${uri}\n${ts}\n${nonce}\n${bodyHash}`;
    const sig = b64(this.signer.sign(enc.encode(msg)));
    return `Chat-Sig name=${this.signer.name},dev=${this.signer.deviceId},ts=${ts},nonce=${nonce},sig=${sig}`;
  }

  /** WebSocket-Auth-Nachricht (Body-Hash "WS"). */
  async wsAuth(): Promise<string> {
    const h = await this.authHeader('GET', '/v1/stream', 'WS');
    const m = Object.fromEntries(
      h
        .slice('Chat-Sig '.length)
        .split(',')
        .map((kv) => {
          const i = kv.indexOf('=');
          return [kv.slice(0, i), kv.slice(i + 1)];
        }),
    );
    return JSON.stringify(m);
  }

  wsUrl(): string {
    return this.base.replace(/^http/, 'ws') + '/v1/stream';
  }

  async call<T = unknown>(method: string, uri: string, body?: unknown, ok: number[] = [200, 201, 202, 204]): Promise<T> {
    const raw = body === undefined ? new Uint8Array() : enc.encode(JSON.stringify(body));
    const bodyHash = hex(await sha256(raw));
    const res = await doFetch(this.base + uri, {
      method,
      headers: {
        Authorization: await this.authHeader(method, uri, bodyHash),
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      },
      body: body === undefined ? undefined : (raw as BodyInit),
      referrerPolicy: 'no-referrer',
      cache: 'no-store',
      credentials: 'omit',
    });
    return this.parse<T>(res, ok);
  }

  private async parse<T>(res: Response, ok: number[]): Promise<T> {
    if (!ok.includes(res.status)) {
      let msg = res.statusText;
      try {
        msg = ((await res.json()) as { error?: string }).error ?? msg;
      } catch {
        /* kein JSON */
      }
      throw new ApiError(res.status, msg);
    }
    if (res.status === 204 || res.status === 202) return undefined as T;
    return (await res.json()) as T;
  }

  async publicGet<T>(path: string): Promise<T> {
    const res = await doFetch(this.base + path, { referrerPolicy: 'no-referrer', credentials: 'omit', cache: 'no-store' });
    return this.parse<T>(res, [200]);
  }

  serverInfo(): Promise<ServerInfo> {
    return this.publicGet<ServerInfo>('/v1/server-info');
  }

  async register(req: object): Promise<{ address: string; intro: { mailbox_id: string; send_token: string } }> {
    const res = await doFetch(this.base + '/v1/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(req),
      referrerPolicy: 'no-referrer',
      credentials: 'omit',
    });
    return this.parse(res, [201]);
  }

  /** Neues Gerät eines bestehenden Kontos (beglaubigt vom Konto-Schlüssel, keine Anmeldung nötig). */
  async addDevice(req: object): Promise<void> {
    const res = await doFetch(this.base + '/v1/devices', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(req),
      referrerPolicy: 'no-referrer',
      credentials: 'omit',
    });
    await this.parse(res, [201]);
  }

  /** Datei (Chiffretext) hochladen. Der Body ist nicht Teil der Signatur (UNSIGNED); er ist E2E-authentifiziert. */
  async uploadBlob(data: Uint8Array): Promise<string> {
    const auth = await this.authHeader('POST', '/v1/blobs', 'UNSIGNED');
    const res = await doFetch(this.base + '/v1/blobs', {
      method: 'POST',
      headers: { Authorization: auth, 'X-Body-Hash': 'UNSIGNED', 'Content-Type': 'application/octet-stream' },
      body: data as BodyInit,
      referrerPolicy: 'no-referrer',
      credentials: 'omit',
    });
    return (await this.parse<{ blob_id: string }>(res, [201])).blob_id;
  }

  static async downloadBlob(server: string, id: string): Promise<Uint8Array> {
    const res = await doFetch(`${baseUrl(server)}/v1/blobs/${encodeURIComponent(id)}`, {
      referrerPolicy: 'no-referrer',
      credentials: 'omit',
    });
    if (!res.ok) throw new ApiError(res.status, 'download failed');
    return new Uint8Array(await res.arrayBuffer());
  }

  /** Direkter, anonymer Einwurf beim Ziel-Server (ohne Home-Server-Relay). */
  static async putDirect(domain: string, mailboxId: string, token: string, blob: Uint8Array): Promise<number> {
    const res = await doFetch(`${baseUrl(domain)}/v1/mailboxes/${encodeURIComponent(mailboxId)}/messages`, {
      method: 'PUT',
      headers: { 'X-Send-Token': token },
      body: blob as BodyInit,
      referrerPolicy: 'no-referrer',
      credentials: 'omit',
    });
    return res.status;
  }
}

export { unb64 };
