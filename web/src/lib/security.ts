/** Sicherheitsübersicht: fasst zusammen, was für die Sicherheit des Kontos gerade gilt, und hebt Auffälliges hervor. */
import type { Engine } from './engine';
import type { Conversation } from './types';
import { isInsecureTransport } from './util';

export type Level = 'ok' | 'info' | 'warn' | 'bad';

export interface SecItem {
  level: Level;
  title: string;
  detail: string;
}

const RANK: Record<Level, number> = { ok: 0, info: 0, warn: 1, bad: 2 };

export function worst(levels: Level[]): Level {
  return levels.reduce<Level>((a, b) => (RANK[b] > RANK[a] ? b : a), 'ok');
}

/** Sicherheitsstatus eines Chats: Schlüsseländerung (rot), unverifizierte Gegenüber (gelb/grau), alles verifiziert (grün). */
export function convSecurity(e: Engine, conv: Conversation): { level: Level; label: string } {
  const s = e.state!;
  if (conv.warning) return { level: 'bad', label: 'Schlüssel geändert' };
  const others = [...new Set(conv.members.map((m) => m.address))].filter((a) => a !== s.me.address);
  if (others.length === 0) return { level: 'info', label: 'Ende-zu-Ende verschlüsselt' };
  const unverified = others.filter((a) => !s.contacts[a]?.verified);
  if (unverified.length === 0) return { level: 'ok', label: 'Verifiziert' };
  return { level: 'info', label: conv.kind === 'dm' ? 'Nicht verifiziert' : `${unverified.length} nicht verifiziert` };
}

export function securityReport(e: Engine): { level: Level; items: SecItem[] } {
  const s = e.state!;
  const items: SecItem[] = [];
  const insecure = isInsecureTransport();

  items.push(
    insecure
      ? { level: 'warn', title: 'Verbindung ohne TLS (http)', detail: 'Nachrichten bleiben Ende-zu-Ende verschlüsselt, aber jemand im Netzwerk kann diese App manipulieren und Schlüssel abgreifen. Nur in vertrauenswürdigen Netzen verwenden.' }
      : location.protocol === 'https:'
        ? { level: 'ok', title: 'Verbindung verschlüsselt (TLS)', detail: 'Die App wurde über eine verschlüsselte Verbindung geladen.' }
        : { level: 'info', title: 'Lokale Verbindung (localhost)', detail: 'Der Server läuft auf diesem Rechner; der Verkehr verlässt ihn nicht.' },
  );
  items.push({ level: e.online ? 'ok' : 'warn', title: e.online ? `Mit ${s.me.domain} verbunden` : 'Offline', detail: e.online ? 'Echtzeit-Verbindung steht.' : 'Nachrichten werden gesendet, sobald die Verbindung wieder da ist.' });
  items.push({ level: 'ok', title: 'Ende-zu-Ende-Verschlüsselung (MLS, RFC 9420)', detail: 'Server sehen weder Texte noch Dateien, Dateinamen, Gruppen oder Reaktionen. Vorwärts- und Nach-Kompromittierungs-Sicherheit durch laufende Schlüsselerneuerung.' });
  items.push({ level: 'ok', title: 'Lokaler Speicher verschlüsselt', detail: 'Schlüssel und Verlauf liegen nur mit deiner Passphrase (Argon2id) verschlüsselt auf diesem Gerät.' });

  items.push(
    s.backupDone
      ? { level: 'ok', title: 'Backup-Datei gespeichert', detail: 'Du kannst dich auf weiteren Geräten anmelden und dein Konto wiederherstellen.' }
      : { level: 'bad', title: 'Kein Backup gespeichert', detail: 'Ohne Backup-Datei ist dein Konto bei Geräteverlust weg.' },
  );

  const alerts = s.alerts ?? [];
  for (const a of alerts) items.push({ level: a.kind === 'key' ? 'bad' : 'warn', title: a.kind === 'key' ? 'Schlüssel eines Kontakts geändert' : 'Neues Gerät im Konto', detail: a.text });

  const devs = s.knownDevices?.length ?? 1;
  items.push({ level: 'info', title: `${devs} ${devs === 1 ? 'Gerät' : 'Geräte'} aktiv`, detail: 'Unbekannte Geräte kannst du unter Einstellungen → Geräte widerrufen.' });

  const people = Object.values(s.contacts);
  const verified = people.filter((c) => c.verified).length;
  items.push({
    level: 'info',
    title: `${verified} von ${people.length} Kontakten verifiziert`,
    detail: people.length === 0 ? 'Noch keine Kontakte.' : 'Vergleiche die Sicherheitsnummer über einen anderen Kanal (persönlich, Telefon), um Manipulation durch Server auszuschließen.',
  });

  const warned = Object.values(s.conversations).filter((c) => c.warning);
  for (const c of warned) items.push({ level: 'bad', title: `Warnung in „${c.title}“`, detail: c.warning! });

  const timers = Object.values(s.conversations).filter((c) => c.disappearSeconds > 0).length;
  if (timers) items.push({ level: 'info', title: `Verschwindende Nachrichten in ${timers} Chats`, detail: 'Nachrichten werden nach der eingestellten Zeit lokal gelöscht.' });

  items.push({
    level: 'info',
    title: `Bestätigungen: Empfangen ${s.sendDelivered ? 'an' : 'aus'}, Gelesen ${s.sendRead ? 'an' : 'aus'}`,
    detail: 'Standardmäßig aus (weniger Metadaten). Gegenseitig: Wer sie ausschaltet, sieht die der anderen nicht.',
  });

  if (e.info?.client_hash) items.push({ level: 'info', title: 'Web-Client-Prüfsumme des Servers', detail: `${e.info.client_hash.slice(0, 32)}… – vergleiche sie mit der veröffentlichten Prüfsumme der Version, der du vertraust (ein manipulierter Server könnte veränderten Code ausliefern; native Apps sind davor besser geschützt).` });

  return { level: worst(items.map((i) => i.level)), items };
}
