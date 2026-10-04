/**
 * „Angemeldet bleiben“: Die Passphrase wird für die gewählte Zeit auf diesem Gerät vorgehalten, damit ein Neuladen nicht erneut danach fragt.
 * - Tab: nur im sessionStorage (verschwindet beim Schließen des Tabs).
 * - Zeitlich (1 h … 7 Tage): mit einem nicht exportierbaren WebCrypto-Schlüssel verschlüsselt in IndexedDB. Das schützt nicht vor Schadcode in dieser Seite
 *   oder jemandem mit Zugriff auf das laufende Browser-Profil; deshalb ist es standardmäßig aus. Ohne sicheren Kontext (kein TLS) gibt es nur „Tab“.
 * Explizites Sperren/Abmelden und die Inaktivitäts-Sperre löschen alles sofort.
 */
import { kv } from './db';
import { rememberMode, type RememberMode } from './prefs';

const SESS = 'chat.sess';
const DURATION: Record<Exclude<RememberMode, 'off' | 'tab'>, number> = { '1h': 3600e3, '8h': 8 * 3600e3, '7d': 7 * 24 * 3600e3 };

export const persistentAvailable = (): boolean => typeof crypto !== 'undefined' && !!crypto.subtle && !!globalThis.indexedDB;

export async function saveSession(pass: string, mode: RememberMode = rememberMode()): Promise<void> {
  await clearSession();
  if (mode === 'off') return;
  if (mode === 'tab' || !persistentAvailable()) {
    try { sessionStorage.setItem(SESS, JSON.stringify({ p: pass })); } catch { /* egal */ }
    return;
  }
  const key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, new TextEncoder().encode(pass)));
  await kv.put(SESS, { key, iv, ct, exp: Date.now() + DURATION[mode] });
}

export async function loadSession(): Promise<string | null> {
  try {
    const t = sessionStorage.getItem(SESS);
    if (t) return (JSON.parse(t) as { p: string }).p;
  } catch { /* egal */ }
  if (!persistentAvailable()) return null;
  try {
    const r = await kv.get<{ key: CryptoKey; iv: Uint8Array; ct: Uint8Array; exp: number }>(SESS);
    if (!r) return null;
    if (Date.now() > r.exp) {
      await clearSession();
      return null;
    }
    return new TextDecoder().decode(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: r.iv as BufferSource }, r.key, r.ct as BufferSource));
  } catch {
    await clearSession();
    return null;
  }
}

export async function clearSession(): Promise<void> {
  try { sessionStorage.removeItem(SESS); } catch { /* egal */ }
  try { await kv.del(SESS); } catch { /* egal */ }
}
