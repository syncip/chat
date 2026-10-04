/** Geräte-Einstellungen (nur dieses Gerät, nicht synchronisiert): Passphrase-Mindestlänge, Angemeldet bleiben, automatische Sperre. */
const get = (k: string): string | null => { try { return localStorage.getItem(k); } catch { return null; } };
const set = (k: string, v: string) => { try { localStorage.setItem(k, v); } catch { /* nicht verfügbar */ } };

/** Mindestlänge der Passphrase: vom Admin des Servers festgelegt (zuletzt gemeldeter Wert, Standard 8). Die Passphrase wird mit Argon2id gehärtet. */
export function minPassLength(): number {
  const n = Number(get('chat.minPass'));
  return Number.isInteger(n) && n >= 1 && n <= 128 ? n : 8;
}
export function setMinPassLength(n: number): void {
  set('chat.minPass', String(Math.min(128, Math.max(1, Math.round(n) || 8))));
}

export type RememberMode = 'off' | 'tab' | '1h' | '8h' | '7d';
export const REMEMBER_LABEL: Record<RememberMode, string> = {
  off: 'Nie (nach jedem Neuladen Passphrase eingeben)',
  tab: 'Solange dieser Tab/dieses Fenster offen ist',
  '1h': '1 Stunde',
  '8h': '8 Stunden',
  '7d': '7 Tage',
};
export function rememberMode(): RememberMode {
  const v = get('chat.remember');
  return v === 'tab' || v === '1h' || v === '8h' || v === '7d' ? v : 'off';
}
export function setRememberMode(m: RememberMode): void {
  set('chat.remember', m);
}

/** Automatische Sperre nach Inaktivität in Minuten (0 = aus). */
export function idleLockMinutes(): number {
  const n = Number(get('chat.idleLock'));
  return Number.isFinite(n) && n >= 0 ? n : 0;
}
export function setIdleLockMinutes(n: number): void {
  set('chat.idleLock', String(Math.max(0, Math.round(n))));
}
