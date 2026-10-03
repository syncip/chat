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

export async function sha256(b: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest('SHA-256', b as BufferSource));
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

/** Schema für Server-URLs: https, außer für lokale Entwicklung. */
export function baseUrl(domain: string): string {
  const host = domain.split(':')[0];
  const local = host === 'localhost' || host === '127.0.0.1' || host === '[::1]';
  return `${local ? 'http' : 'https'}://${domain}`;
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
