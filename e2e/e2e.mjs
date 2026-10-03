// End-to-End-Test: zwei Server (föderiert) + zwei Browser. Benötigt: gebautes Web-Bundle, chatd-Binary, Playwright.
// Aufruf: CHATD=/pfad/chatd WEB_DIR=../web/dist node e2e.mjs
import { spawn } from 'node:child_process';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createRequire } from 'node:module';
import assert from 'node:assert/strict';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const CHATD = process.env.CHATD ?? '../server/chatd';
const WEB = process.env.WEB_DIR ?? '../web/dist';
const EXE = process.env.CHROME ?? undefined;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function startServer(port) {
  const dir = mkdtempSync(join(tmpdir(), 'chat-e2e-'));
  const p = spawn(CHATD, [], {
    env: {
      ...process.env, CHAT_DOMAIN: `localhost:${port}`, CHAT_LISTEN: `127.0.0.1:${port}`, CHAT_DATA_DIR: dir, CHAT_WEB_DIR: WEB,
      CHAT_ADMIN_KEY: 'e2e-admin-key', CHAT_FEDERATION_INSECURE_HTTP: 'true', CHAT_USER_INVITES: 'true', CHAT_RATE_PER_MINUTE: '100000', CHAT_CSP_CONNECT_EXTRA: 'http://localhost:*',
    },
    stdio: ['ignore', 'inherit', 'inherit'],
  });
  return p;
}

async function invite(port) {
  const r = await fetch(`http://localhost:${port}/v1/admin/invites`, { method: 'POST', headers: { 'X-Admin-Key': 'e2e-admin-key' } });
  assert.equal(r.status, 201);
  return (await r.json()).invite;
}

async function waitUp(port) {
  for (let i = 0; i < 50; i++) {
    try { if ((await fetch(`http://localhost:${port}/v1/server-info`)).ok) return; } catch {}
    await sleep(100);
  }
  throw new Error('server did not start');
}

async function register(page, port, name, pass) {
  await page.goto(`http://localhost:${port}/`);
  await page.getByLabel('Server').fill(`localhost:${port}`);
  await page.getByLabel('Benutzername').fill(name);
  await page.getByLabel('Einladungscode').fill(await invite(port));
  await page.getByLabel(/^Passphrase \(/).fill(pass);
  await page.getByLabel('Passphrase wiederholen').fill(pass);
  await page.getByRole('button', { name: 'Konto erstellen' }).click();
  await page.getByText('● verbunden').waitFor({ timeout: 30000 });
}

const servers = [startServer(18080), startServer(18081)];
let browser;
try {
  await Promise.all([waitUp(18080), waitUp(18081)]);
  browser = await chromium.launch({ executablePath: EXE, args: ['--no-sandbox'] });
  const ctxA = await browser.newContext({ acceptDownloads: true });
  const ctxB = await browser.newContext({ acceptDownloads: true });
  const alice = await ctxA.newPage();
  const bob = await ctxB.newPage();
  for (const [n, p] of [['alice', alice], ['bob', bob]]) p.on('pageerror', (e) => console.error(`[${n}] pageerror`, e.message));
  const pass = 'correct horse battery';

  await register(alice, 18080, 'alice', pass);
  await register(bob, 18081, 'bob', pass); // anderer Server → Föderation
  console.log('✔ Registrierung auf zwei Servern');

  // Alice teilt ihren Kontaktlink
  await alice.getByTitle('Einstellungen').click();
  const link = await alice.locator('input[readonly]').first().inputValue();
  assert.match(link, /#\/add\//);
  await alice.keyboard.press('Escape');

  // Bob startet den Chat (Server B → Server A)
  await bob.getByTitle('Neuer Chat').click();
  await bob.getByPlaceholder('https://…/#/add/…').fill(link);
  await bob.getByRole('button', { name: 'Chat starten' }).click();
  await bob.locator('.conv .title', { hasText: 'alice@localhost:18080' }).waitFor({ timeout: 20000 });
  console.log('✔ Bob startet Chat mit Alice (über Server-Grenze)');

  // Alice erhält Anfrage und nimmt an
  await alice.getByText('Anfragen').waitFor({ timeout: 20000 });
  await alice.locator('.requests .conv').first().click();
  await alice.getByRole('button', { name: 'Annehmen' }).click();
  console.log('✔ Alice nimmt die Anfrage an');

  // Bob sendet Text, Alice antwortet mit Code
  const bobBox = bob.getByPlaceholder('Nachricht schreiben …');
  await bobBox.fill('Hallo Alice, **verschlüsselt**!');
  await bobBox.press('Enter');
  await alice.getByText('verschlüsselt', { exact: false }).first().waitFor({ timeout: 20000 });
  console.log('✔ Text Bob → Alice');

  await alice.getByPlaceholder('Nachricht schreiben …').fill('Hi Bob');
  await alice.getByPlaceholder('Nachricht schreiben …').press('Enter');
  await bob.locator('.messages p.text', { hasText: 'Hi Bob' }).waitFor({ timeout: 20000 });
  console.log('✔ Text Alice → Bob');

  await alice.getByTitle('Codeblock').click();
  await alice.getByPlaceholder('Sprache').fill('js');
  await alice.getByPlaceholder('Code …').fill('console.log("<b>nicht html</b>");');
  await alice.getByRole('button', { name: 'Senden' }).click();
  await bob.locator('pre.code code').getByText('<b>nicht html</b>', { exact: false }).waitFor({ timeout: 20000 });
  assert.equal(await bob.locator('pre.code b').count(), 0, 'HTML darf nicht interpretiert werden');
  console.log('✔ Codeblock (ohne HTML-Interpretation)');

  // Zitat
  await bob.locator('.msg', { hasText: 'Hi Bob' }).getByLabel('Aktionen').click();
  await bob.getByRole('button', { name: 'Antworten' }).click();
  await bob.getByPlaceholder('Nachricht schreiben …').fill('Zitat-Antwort');
  await bob.getByPlaceholder('Nachricht schreiben …').press('Enter');
  await alice.locator('blockquote', { hasText: 'Hi Bob' }).waitFor({ timeout: 20000 });
  console.log('✔ Zitat');

  // Datei (Binärdatei) senden und prüfen
  const bytes = Buffer.alloc(150_000);
  for (let i = 0; i < bytes.length; i++) bytes[i] = (i * 7) & 0xff;
  const tmp = join(mkdtempSync(join(tmpdir(), 'f-')), 'setup.exe');
  writeFileSync(tmp, bytes);
  await bob.locator('input[type=file]').setInputFiles(tmp);
  await bob.getByRole('button', { name: 'Senden' }).click();
  await alice.locator('.file-name', { hasText: 'setup.exe' }).waitFor({ timeout: 30000 });
  const [dl] = await Promise.all([alice.waitForEvent('download'), alice.locator('.file').getByRole('button', { name: 'Speichern' }).click()]);
  const got = Buffer.from(await (await import('node:fs/promises')).readFile(await dl.path()));
  assert.ok(got.equals(bytes), 'Datei muss bitgenau ankommen');
  console.log('✔ Datei (150 kB, .exe) Ende-zu-Ende verschlüsselt übertragen');

  // Blockieren: Alice blockiert Bob → neue Nachricht kommt nicht mehr an
  await alice.getByTitle('Einstellungen').click();
  await alice.getByPlaceholder('name@server', { exact: true }).fill('bob@localhost:18081');
  await alice.getByRole('button', { name: 'Hinzufügen' }).first().click();
  await alice.getByText('bob@localhost:18081').first().waitFor();
  await alice.keyboard.press('Escape');
  await bob.getByPlaceholder('Nachricht schreiben …').fill('nach dem Blockieren');
  await bob.getByPlaceholder('Nachricht schreiben …').press('Enter');
  await sleep(3000);
  assert.equal(await alice.getByText('nach dem Blockieren').count(), 0, 'blockierte Nachricht darf nicht erscheinen');
  console.log('✔ Blockieren verwirft Nachrichten stillschweigend');

  // Persistenz: Alice sperren und entsperren (Verlauf bleibt)
  await alice.getByTitle('Einstellungen').click();
  await alice.getByRole('button', { name: 'Sperren' }).click();
  await alice.getByLabel('Passphrase', { exact: true }).fill(pass);
  await alice.getByRole('button', { name: 'Entsperren' }).click();
  await alice.locator('.conv').first().click();
  await alice.locator('.messages p.text', { hasText: 'Hi Bob' }).waitFor({ timeout: 30000 });
  console.log('✔ Sperren/Entsperren, Verlauf bleibt erhalten');
  console.log('\nALLE E2E-TESTS BESTANDEN');
} finally {
  await browser?.close();
  servers.forEach((s) => s.kill());
}
