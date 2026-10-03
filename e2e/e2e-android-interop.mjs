// Interop: Web-Client (Browser) ↔ Kotlin-Engine (die Logik der Android-App) über denselben Server.
// Voraussetzung: gebautes Web-Bundle, chatd, Gradle + Rust (für die Kotlin-Engine).
import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';
import { chromium, CHROME, H, startServer, waitUp, register, invite, send, seen, sleep, connectToLink } from './helpers.mjs';

const GRADLE_DIR = process.env.ANDROID_DIR ?? '../android';
const server = startServer(18095);
let browser, peer;
try {
  await waitUp(18095);
  const dom = `${H}:18095`;
  const linkFile = join(mkdtempSync(join(tmpdir(), 'peer-')), 'link.txt');
  peer = spawn('gradle', ['-q', ':engine:runPeer', `-PpeerArgs=${dom} ${await invite(18095)} kotlin ${linkFile} 120`], { cwd: GRADLE_DIR, stdio: ['ignore', 'pipe', 'inherit'] });
  let ready = false;
  peer.stdout.on('data', (d) => { if (String(d).includes('PEER READY')) ready = true; });
  for (let i = 0; i < 600 && !ready; i++) await sleep(200);
  assert.ok(ready && existsSync(linkFile), 'Kotlin-Peer startet nicht');
  const link = readFileSync(linkFile, 'utf8').trim();

  browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox', '--no-proxy-server'] });
  const web = await (await browser.newContext({ acceptDownloads: true })).newPage();
  web.on('pageerror', (e) => console.error('[web] pageerror', e.message));
  await register(web, 18095, 'webuser');
  await connectToLink(web, link);
  console.log('✔ Web startet Chat mit Kotlin-Peer, Anfrage wurde angenommen');

  await send(web, 'Hallo vom Browser');
  await seen(web, 'echo: Hallo vom Browser');
  console.log('✔ Text Web → Kotlin → Web');

  await web.getByTitle('Codeblock').click();
  await web.getByPlaceholder('Sprache').fill('rust');
  await web.getByPlaceholder('Code …').fill('fn main() {}');
  await web.getByRole('button', { name: 'Senden' }).click();
  await seen(web, 'code rust: fn main() {}');
  console.log('✔ Codeblock Web → Kotlin');

  const bytes = Buffer.alloc(200_000);
  for (let i = 0; i < bytes.length; i++) bytes[i] = (i * 13 + 5) & 0xff;
  const f = join(mkdtempSync(join(tmpdir(), 'f-')), 'daten.bin');
  writeFileSync(f, bytes);
  await web.locator('input[type=file]').setInputFiles(f);
  await web.getByRole('button', { name: 'Senden' }).click();
  await seen(web, `file ok ${createHash('sha256').update(bytes).digest('hex')} ${bytes.length}`);
  console.log('✔ Datei (200 kB) Web → Kotlin: Hash stimmt Ende-zu-Ende');
  console.log('\nINTEROP-E2E BESTANDEN');
} finally {
  await browser?.close();
  peer?.kill();
  server.kill();
}
