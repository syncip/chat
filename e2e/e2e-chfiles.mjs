// Dateien in Kanälen: posten, in „Alle Dateien“ als eigene Dateien sehen und löschen.
import assert from 'node:assert/strict';
import { chromium, CHROME, startServer, waitUp, register } from './helpers.mjs';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);

const P = 18109;
const server = startServer(P);
let browser;
function crc32(buf) {
  let c, crc = ~0;
  for (const b of buf) { c = (crc ^ b) & 0xff; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; crc = (crc >>> 8) ^ c; }
  return ~crc >>> 0;
}
function png() { // 2x2 rotes PNG
  const chunk = (t, d) => { const l = Buffer.alloc(4); l.writeUInt32BE(d.length); const td = Buffer.concat([Buffer.from(t), d]); const c = Buffer.alloc(4); c.writeUInt32BE(crc32(td)); return Buffer.concat([l, td, c]); };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(2, 0); ihdr.writeUInt32BE(2, 4); ihdr[8] = 8; ihdr[9] = 2;
  const raw = Buffer.from([0, 255, 0, 0, 255, 0, 0, 0, 255, 0, 0, 255, 0, 0, 0, 0].slice(0, 14)); // zwei Zeilen à 1+6 Byte
  const rows = Buffer.concat([Buffer.from([0]), Buffer.from([255, 0, 0, 255, 0, 0]), Buffer.from([0]), Buffer.from([0, 0, 255, 0, 0, 255])]);
  void raw;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', require('node:zlib').deflateSync(rows)), chunk('IEND', Buffer.alloc(0))]);
}
function wav() { // 0,1 s Stille, 8 kHz mono 8 bit
  const n = 800, h = Buffer.alloc(44);
  h.write('RIFF', 0); h.writeUInt32LE(36 + n, 4); h.write('WAVEfmt ', 8); h.writeUInt32LE(16, 16); h.writeUInt16LE(1, 20); h.writeUInt16LE(1, 22);
  h.writeUInt32LE(8000, 24); h.writeUInt32LE(8000, 28); h.writeUInt16LE(1, 32); h.writeUInt16LE(8, 34); h.write('data', 36); h.writeUInt32LE(n, 40);
  return Buffer.concat([h, Buffer.alloc(n, 128)]);
}

try {
  await waitUp(P);
  browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox', '--no-proxy-server'] });
  const page = await (await browser.newContext()).newPage();
  await register(page, P, 'alice');
  await page.getByTitle('Hinzufügen').click();
  await page.getByRole('button', { name: 'Kanal erstellen' }).click();
  const d = page.getByRole('dialog', { name: 'Neuer öffentlicher Kanal' });
  await d.getByLabel('Name').fill('Bilder');
  await d.getByRole('button', { name: 'Kanal erstellen' }).click();
  await page.locator('.chat-header', { hasText: 'Bilder' }).waitFor({ timeout: 20000 });

  await page.locator('input[type=file]').setInputFiles({ name: 'rot.png', mimeType: 'image/png', buffer: png() });
  await page.getByText('rot.png').first().waitFor();
  await page.getByPlaceholder('Beitrag schreiben …').fill('Mein Bild');
  await page.getByRole('button', { name: 'Senden' }).click();
  await page.locator('.msg img').first().waitFor({ timeout: 20000 });
  console.log('✔ Bild in Kanal gepostet und inline angezeigt');

  await page.getByLabel('Alle Dateien').click();
  const dlg = page.getByRole('dialog', { name: 'Alle Dateien' });
  await dlg.getByText('📢 Bilder').waitFor();
  await dlg.getByLabel(/Nur meine Dateien/).check();
  assert.equal(await dlg.locator('.files-item').count(), 1, 'Kanal-Datei zählt als eigene Datei');
  page.once('dialog', (x) => x.accept());
  await dlg.getByRole('button', { name: 'Löschen' }).click();
  await dlg.getByText('Keine Dateien.').waitFor({ timeout: 15000 });
  await page.keyboard.press('Escape');
  await page.getByText('Beitrag entfernt').waitFor({ timeout: 15000 });
  console.log('✔ Kanal-Datei in Dateiansicht gelöscht (Beitrag entfernt)');
  console.log('CHFILES-E2E BESTANDEN');
} catch (e) {
  console.error('FAIL', e);
  process.exitCode = 1;
} finally {
  await browser?.close();
  server.kill();
}
