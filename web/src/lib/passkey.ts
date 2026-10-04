/**
 * Entsperren per Passkey (WebAuthn + PRF-Erweiterung).
 * Der Passkey liefert über die PRF-Erweiterung ein nur dem Authenticator (Fingerabdruck/PIN/Sicherheitsschlüssel/Geräte-Passkey) bekanntes
 * Geheimnis; damit wird die Passphrase verschlüsselt auf diesem Gerät abgelegt. Beim Entsperren liefert der Passkey (nach Biometrie/PIN)
 * dasselbe Geheimnis wieder, die Passphrase wird entschlüsselt und der Tresor wie gewohnt geöffnet. Die Passphrase bleibt der Hauptschlüssel
 * (Backup, neue Geräte); der Passkey ist eine Komfort-Abkürzung nur für dieses Gerät. Erfordert einen sicheren Kontext (https oder localhost)
 * und einen Browser/Authenticator mit PRF-Unterstützung.
 */
import { kv } from './db';

const KEY = 'passkey';
interface Stored { id: Uint8Array; salt: Uint8Array; iv: Uint8Array; ct: Uint8Array }

const rnd = (n: number) => crypto.getRandomValues(new Uint8Array(n));
const buf = (u: Uint8Array) => u as BufferSource;

export const passkeySupported = (): boolean =>
  typeof window !== 'undefined' && window.isSecureContext && !!navigator.credentials && typeof PublicKeyCredential !== 'undefined' && !!crypto.subtle;

export async function hasPasskey(): Promise<boolean> {
  try { return !!(await kv.get<Stored>(KEY)); } catch { return false; }
}

export async function removePasskey(): Promise<void> {
  try { await kv.del(KEY); } catch { /* egal */ }
}

async function prfSecret(id: Uint8Array | null, salt: Uint8Array): Promise<{ secret: Uint8Array; id: Uint8Array } | null> {
  const ext = { prf: { eval: { first: salt } } } as AuthenticationExtensionsClientInputs;
  const cred = (await navigator.credentials.get({
    publicKey: { challenge: buf(rnd(32)), allowCredentials: id ? [{ type: 'public-key', id: buf(id) }] : [], userVerification: 'required', timeout: 60000, extensions: ext },
  })) as PublicKeyCredential | null;
  if (!cred) return null;
  const r = (cred.getClientExtensionResults() as { prf?: { results?: { first?: ArrayBuffer } } }).prf?.results?.first;
  return r ? { secret: new Uint8Array(r), id: new Uint8Array(cred.rawId) } : null;
}

async function wrapKey(secret: Uint8Array): Promise<CryptoKey> {
  const base = await crypto.subtle.importKey('raw', buf(secret), 'HKDF', false, ['deriveKey']);
  return crypto.subtle.deriveKey({ name: 'HKDF', hash: 'SHA-256', salt: new Uint8Array(0), info: new TextEncoder().encode('chat passkey vault v1') }, base, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
}

/** Legt einen Passkey an und bindet die (bereits geprüfte) Passphrase daran. */
export async function enrollPasskey(passphrase: string, label: string): Promise<void> {
  if (!passkeySupported()) throw new Error('Passkeys brauchen eine sichere Verbindung (https oder localhost) und einen unterstützten Browser.');
  const created = (await navigator.credentials.create({
    publicKey: {
      rp: { name: 'Chat' },
      user: { id: buf(rnd(16)), name: label, displayName: label },
      challenge: buf(rnd(32)),
      pubKeyCredParams: [{ type: 'public-key', alg: -7 }, { type: 'public-key', alg: -257 }],
      authenticatorSelection: { residentKey: 'preferred', userVerification: 'required' },
      timeout: 60000,
      extensions: { prf: {} } as AuthenticationExtensionsClientInputs,
    },
  })) as PublicKeyCredential | null;
  if (!created) throw new Error('Passkey-Erstellung abgebrochen.');
  const enabled = (created.getClientExtensionResults() as { prf?: { enabled?: boolean } }).prf?.enabled;
  if (enabled === false) throw new Error('Dieser Passkey/Browser unterstützt die PRF-Erweiterung nicht (nötig zum Entsperren).');
  const salt = rnd(32);
  const got = await prfSecret(new Uint8Array(created.rawId), salt);
  if (!got) throw new Error('Dieser Passkey/Browser unterstützt die PRF-Erweiterung nicht (nötig zum Entsperren).');
  const iv = rnd(12);
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv: buf(iv) }, await wrapKey(got.secret), new TextEncoder().encode(passphrase)));
  await kv.put(KEY, { id: got.id, salt, iv, ct } satisfies Stored);
}

/** Fragt den Passkey ab und liefert die Passphrase; null bei Abbruch/Fehler. */
export async function passkeyPassphrase(): Promise<string | null> {
  const st = await kv.get<Stored>(KEY);
  if (!st) return null;
  const got = await prfSecret(st.id, st.salt);
  if (!got) throw new Error('Der Passkey lieferte kein Geheimnis (PRF nicht unterstützt).');
  try {
    return new TextDecoder().decode(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: buf(st.iv) }, await wrapKey(got.secret), buf(st.ct)));
  } catch {
    throw new Error('Falscher Passkey für dieses Konto.');
  }
}
