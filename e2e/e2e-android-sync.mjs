// Interop: Der Web-Client erstellt Kanäle, ein Kotlin-Gerät (Logik der Android-App) desselben Kontos muss sie über den Konto-Sync sehen.
import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync, copyFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import assert from 'node:assert/strict';
import { chromium, CHROME, startServer, waitUp, register, sleep, BACKUP_PASS } from './helpers.mjs';

const GRADLE_DIR = process.env.ANDROID_DIR ?? '../android';
const P = 18113;
const server = startServer(P);
let browser, peer;
const status = join(mkdtempSync(join(tmpdir(), 'syncpeer-')), 'status.txt');
const read = () => (existsSync(status) ? readFileSync(status, 'utf8') : '');
async function until(pred, what, ms = 40000) {
  const t = Date.now();
  while (Date.now() - t < ms) { if (pred(read())) return; await sleep(300); }
  throw new Error(`Zeitüberschreitung: ${what} (Status: ${read()})`);
}
try {
  await waitUp(P);
  browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox', '--no-proxy-server'] });
  const web = await (await browser.newContext({ acceptDownloads: true })).newPage();
  const backup = await register(web, P, 'alice');
  const bfile = join(mkdtempSync(join(tmpdir(), 'bk-')), 'backup.bak');
  copyFileSync(backup, bfile);
  peer = spawn('./gradlew', ['--offline', '-q', ':engine:runSyncPeer', `-PpeerArgs=${bfile} - ${status} 150`], { cwd: GRADLE_DIR, env: { ...process.env, PEER_BACKUP_PASS: BACKUP_PASS }, stdio: ['ignore', 'pipe', 'inherit'] });
  let ready = false;
  peer.stdout.on('data', (d) => { if (String(d).includes('PEER READY')) ready = true; });
  for (let i = 0; i < 900 && !ready; i++) await sleep(200);
  assert.ok(ready, 'Kotlin-Gerät startet nicht');
  await until((s) => s.startsWith('CHANNELS:'), 'Kotlin-Gerät verbunden');

  // Kanal im Browser anlegen, während das Kotlin-Gerät bereits angemeldet ist
  await web.getByTitle('Hinzufügen').click();
  await web.getByRole('button', { name: 'Kanal erstellen' }).click();
  const d = web.getByRole('dialog', { name: 'Neuer öffentlicher Kanal' });
  await d.getByLabel('Name').fill('VomBrowser');
  await d.getByRole('button', { name: 'Kanal erstellen' }).click();
  await web.locator('.chat-header', { hasText: 'VomBrowser' }).waitFor({ timeout: 20000 });
  await until((s) => s.includes('VomBrowser'), 'Kanal aus dem Browser erscheint im Kotlin-Gerät');
  console.log('✔ Web-Kanal erscheint auf dem Kotlin-Gerät');

  // öffentlicher Kanal
  await web.getByTitle('Hinzufügen').click();
  await web.getByRole('button', { name: 'Kanal erstellen' }).click();
  const d2 = web.getByRole('dialog', { name: 'Neuer öffentlicher Kanal' });
  await d2.getByLabel('Name').fill('Oeffentlich');
  await d2.getByLabel(/Öffentlich sichtbar/).check();
  await d2.getByRole('button', { name: 'Kanal erstellen' }).click();
  await web.locator('.chat-header', { hasText: 'Oeffentlich' }).waitFor({ timeout: 20000 });
  await until((s) => s.includes('PUBLIC: Oeffentlich'), 'öffentlicher Kanal erscheint');
  console.log('✔ Öffentlicher Web-Kanal erscheint auf dem Kotlin-Gerät');
  console.log('ANDROID-SYNC-E2E BESTANDEN');
} catch (e) {
  console.error('FAIL', e);
  process.exitCode = 1;
} finally {
  peer?.kill();
  await browser?.close();
  server.kill();
}
