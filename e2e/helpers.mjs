import { spawn } from 'node:child_process';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createRequire } from 'node:module';
import assert from 'node:assert/strict';

const require = createRequire(import.meta.url);
export const { chromium } = require('playwright');
const CHATD = process.env.CHATD ?? '../server/chatd';
const WEB = process.env.WEB_DIR ?? '../web/dist';
export const CHROME = process.env.CHROME ?? undefined;
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
// CHAT_HOST: Hostname/IP unter dem die Server erreichbar sind. Eine Nicht-Loopback-IP testet den
// „insecure context“ (kein WebCrypto/Clipboard) wie bei IP:PORT-Betrieb.
export const H = process.env.CHAT_HOST ?? 'localhost';
export const PASS = 'correct horse battery';
export const BACKUP_PASS = 'backup phrase 12345';

export function startServer(port) {
  const dir = mkdtempSync(join(tmpdir(), 'chat-e2e-'));
  return spawn(CHATD, [], {
    env: {
      ...process.env, CHAT_DOMAIN: `${H}:${port}`, CHAT_LISTEN: `0.0.0.0:${port}`, CHAT_DATA_DIR: dir, CHAT_WEB_DIR: WEB,
      CHAT_ADMIN_KEY: 'e2e-admin-key', CHAT_FEDERATION_INSECURE_HTTP: 'true', CHAT_USER_INVITES: 'true', CHAT_RATE_PER_MINUTE: '100000',
      CHAT_CSP_CONNECT_EXTRA: `http://${H}:*`,
    },
    stdio: ['ignore', 'inherit', 'inherit'],
  });
}

export async function invite(port) {
  const r = await fetch(`http://${H}:${port}/v1/admin/invites`, { method: 'POST', headers: { 'X-Admin-Key': 'e2e-admin-key' } });
  assert.equal(r.status, 201);
  return (await r.json()).invite;
}

export async function waitUp(port) {
  for (let i = 0; i < 50; i++) {
    try { if ((await fetch(`http://${H}:${port}/v1/server-info`)).ok) return; } catch {}
    await sleep(100);
  }
  throw new Error('server did not start');
}

/** Registriert ein Konto, erfüllt die Backup-Pflicht und gibt den Pfad der Backup-Datei zurück. */
export async function register(page, port, name, pass = PASS) {
  await page.goto(`http://${H}:${port}/`);
  await page.getByLabel('Server').fill(`${H}:${port}`);
  await page.getByLabel('Benutzername').fill(name);
  await page.getByLabel('Einladungscode').fill(await invite(port));
  await page.getByLabel(/^Passphrase \(/).fill(pass);
  await page.getByLabel('Passphrase wiederholen').fill(pass);
  await page.getByRole('button', { name: 'Konto erstellen' }).click();
  const gate = page.getByRole('dialog', { name: 'Backup speichern' });
  await gate.waitFor({ timeout: 30000 });
  await gate.getByLabel(/Backup-Passphrase/).fill(BACKUP_PASS);
  await gate.getByLabel('Wiederholen').fill(BACKUP_PASS);
  const [dl] = await Promise.all([page.waitForEvent('download'), gate.getByRole('button', { name: 'Backup-Datei speichern' }).click()]);
  const path = await dl.path();
  await gate.getByRole('button', { name: /Ich habe das Backup/ }).click();
  await page.getByText('● verbunden').waitFor({ timeout: 30000 });
  return path;
}

/** Meldet ein weiteres Gerät mit der Backup-Datei an. */
export async function linkDevice(page, port, backupPath, newPass = PASS) {
  await page.goto(`http://${H}:${port}/`);
  await page.getByRole('button', { name: /Mit Backup-Datei auf diesem Gerät anmelden/ }).click();
  await page.locator('input[type=file]').setInputFiles(backupPath);
  await page.getByLabel('Passphrase des Backups').fill(BACKUP_PASS);
  await page.getByLabel(/^Neue Passphrase/).fill(newPass);
  await page.getByLabel('Passphrase wiederholen').fill(newPass);
  await page.getByRole('button', { name: 'Gerät anmelden' }).click();
  await page.getByText('● verbunden').waitFor({ timeout: 30000 });
}

export async function contactLink(page) {
  await page.getByTitle('Einstellungen').click();
  const link = await page.locator('input[readonly]').first().inputValue();
  await page.keyboard.press('Escape');
  return link;
}

/** `from` startet Chat mit Besitzer des Links, `to` nimmt an. */
export async function connect(from, to, link, fromAddr) {
  await from.getByTitle('Neuer Chat').click();
  await from.getByPlaceholder('https://…/#/add/…').fill(link);
  await from.getByRole('button', { name: 'Chat starten' }).click();
  await to.getByText('Anfragen').waitFor({ timeout: 20000 });
  await to.locator('.requests .conv', { hasText: fromAddr }).click();
  await to.getByRole('button', { name: 'Annehmen' }).click();
}

export async function send(page, text) {
  const box = page.getByPlaceholder('Nachricht schreiben …');
  await box.fill(text);
  await box.press('Enter');
}

export const seen = (page, text) => page.locator('.messages p.text', { hasText: text }).first().waitFor({ timeout: 25000 });

/** Chat mit dem Besitzer eines Kontaktlinks starten (Gegenseite nimmt selbst an). */
export async function connectToLink(from, link) {
  await from.getByTitle('Neuer Chat').click();
  await from.getByPlaceholder('https://…/#/add/…').fill(link);
  await from.getByRole('button', { name: 'Chat starten' }).click();
  await from.locator('.chat-header').waitFor({ timeout: 30000 });
}
