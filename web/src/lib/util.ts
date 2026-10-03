export const enc = new TextEncoder();
export const dec = new TextDecoder();

export function b64(b: Uint8Array): string {
  let s = '';
  for (let i = 0; i < b.length; i += 0x8000) s += String.fromCharCode(...b.subarray(i, i + 0x8000));
  return btoa(s);
}

export function unb64(s: string): Uint8Array {
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

export function hex(b: Uint8Array): string {
  return Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
}

export function unhex(s: string): Uint8Array {
  const out = new Uint8Array(s.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(s.slice(i * 2, i * 2 + 2), 16);
  return out;
}

let wasmHasher: ((b: Uint8Array) => Uint8Array) | null = null;

/** Der WASM-Kern liefert SHA-256 auch dort, wo WebCrypto fehlt (http://IP:PORT ist kein „secure context“). */
export function setHasher(f: (b: Uint8Array) => Uint8Array): void {
  wasmHasher = f;
}

export async function sha256(b: Uint8Array): Promise<Uint8Array> {
  if (wasmHasher) return wasmHasher(b);
  return new Uint8Array(await crypto.subtle.digest('SHA-256', b as BufferSource));
}

/** Kopieren, auch ohne Clipboard-API (unsicherer Kontext). */
export async function copyText(text: string): Promise<void> {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text);
    return;
  }
  const ta = document.createElement('textarea');
  ta.value = text;
  ta.style.position = 'fixed';
  ta.style.opacity = '0';
  document.body.appendChild(ta);
  ta.select();
  const ok = document.execCommand('copy');
  ta.remove();
  if (!ok) throw new Error('Kopieren nicht möglich – bitte manuell markieren.');
}

/** Läuft die App ohne TLS (außer localhost)? Dann kann ein Angreifer im Netz den Client manipulieren. */
export function isInsecureTransport(): boolean {
  const h = location.hostname;
  return location.protocol === 'http:' && h !== 'localhost' && h !== '127.0.0.1' && h !== '[::1]';
}

export function randomBytes(n: number): Uint8Array {
  return crypto.getRandomValues(new Uint8Array(n));
}

export function randomId(): string {
  return hex(randomBytes(12));
}

/** `name@domain` zerlegen. */
export function splitAddress(a: string): { name: string; domain: string } | null {
  const m = /^([a-z0-9][a-z0-9._-]{1,31})@([a-z0-9.-]+(?::\d{1,5})?)$/.exec(a.trim().toLowerCase());
  return m ? { name: m[1], domain: m[2] } : null;
}

/** Schema für Server-URLs: https für Domains; http für localhost und IP-Adressen (IP:PORT, kein Zertifikat möglich). */
export function baseUrl(domain: string): string {
  const host = domain.split(':')[0];
  const plain = host === 'localhost' || /^\d{1,3}(\.\d{1,3}){3}$/.test(host);
  return `${plain ? 'http' : 'https'}://${domain}`;
}

export function formatBytes(n: number): string {
  const u = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0;
  let v = n;
  while (v >= 1024 && i < u.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v.toFixed(v >= 10 || i === 0 ? 0 : 1)} ${u[i]}`;
}

/** Findet für offene Registrierung eine Nonce mit `bits` führenden Null-Bits (Proof-of-Work). */
export async function solvePow(name: string, ts: number, bits: number): Promise<string> {
  if (bits <= 0) return '';
  for (let i = 0; ; i++) {
    const nonce = i.toString(36);
    const h = await sha256(enc.encode(`${name}:${ts}:${nonce}`));
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
