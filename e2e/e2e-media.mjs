// Bilder/Audio/Video im Chat, Dateien-Ansicht, Sicherheitsübersicht inkl. Hinweis auf neues Gerät.
import assert from 'node:assert/strict';
import { chromium, CHROME, startServer, waitUp, register, linkDevice, contactLink, connect, send, seen, sleep, H } from './helpers.mjs';

const P = 18102;
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
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);

try {
  await waitUp(P);
  browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox', '--no-proxy-server'] });
  const mk = async () => (await browser.newContext()).newPage();
  const [alice, bob, alice2] = [await mk(), await mk(), await mk()];
  const aliceBackup = await register(alice, P, 'alice');
  await register(bob, P, 'bob');
  await connect(bob, alice, await contactLink(alice), `bob@${H}:${P}`);
  await alice.locator('.conv', { hasText: 'bob' }).click();
  await send(bob, 'hallo');
  await seen(alice, 'hallo');

  const attach = async (name, mimeType, buffer) => {
    await bob.locator('input[type=file]').setInputFiles({ name, mimeType, buffer });
    await bob.locator('.chip', { hasText: name }).waitFor({ timeout: 5000 }).catch(async () => console.log('NOCHIP', await bob.locator('.composer').innerText()));
    await bob.getByRole('button', { name: 'Senden' }).click();
    await bob.locator('.chip').waitFor({ state: 'detached', timeout: 30000 });
  };
  await attach('rot.png', 'image/png', png());
  await attach('ton.wav', 'audio/wav', wav());
  await attach('film.webm', 'video/webm', Buffer.from('nicht wirklich ein video'));
  await attach('setup.exe', 'application/octet-stream', Buffer.from('MZ'));

  // Bild wird direkt angezeigt
  const img = alice.locator('.messages img.media-img');
  await img.waitFor({ timeout: 30000 });
  await alice.waitForFunction(() => { const i = document.querySelector('.messages img.media-img'); return i && i.naturalWidth > 0; }, null, { timeout: 15000 });
  console.log('✔ Bild wird automatisch angezeigt');
  await img.click();
  await alice.locator('.lightbox img').waitFor();
  await alice.keyboard.press('Escape');
  await alice.locator('.lightbox').click();
  console.log('✔ Bild lässt sich vergrößern');

  // Audio/Video erst nach Klick (nichts wird automatisch abgespielt/geladen)
  await alice.getByRole('button', { name: /Audio abspielen/ }).waitFor({ timeout: 30000 });
  assert.equal(await alice.locator('.messages audio').count(), 0);
  await alice.getByRole('button', { name: /Audio abspielen/ }).click();
  await alice.locator('.messages audio').waitFor();
  await alice.getByRole('button', { name: /Video abspielen/ }).click();
  await alice.locator('.messages video').waitFor();
  console.log('✔ Audio und Video mit Player (erst nach Klick)');
  await alice.locator('.file-name', { hasText: 'setup.exe' }).waitFor();

  // Dateien-Ansicht pro Chat und gesamt
  await alice.getByLabel('Dateien', { exact: true }).click();
  let dlg = alice.getByRole('dialog', { name: 'Dateien in diesem Chat' });
  await dlg.getByRole('tab', { name: /Bilder/ }).click();
  await dlg.locator('img.media-img').waitFor({ timeout: 20000 });
  await dlg.getByRole('tab', { name: /Dokumente/ }).click();
  await dlg.locator('.file-name', { hasText: 'setup.exe' }).waitFor();
  await dlg.getByRole('tab', { name: /Audio/ }).click();
  await dlg.locator('.file-name', { hasText: 'ton.wav' }).waitFor();
  await alice.keyboard.press('Escape');
  await alice.getByLabel('Alle Dateien').click();
  dlg = alice.getByRole('dialog', { name: 'Alle Dateien' });
  await dlg.getByRole('tab', { name: /Alle/ }).click();
  assert.equal(await dlg.locator('.files-item').count(), 4);
  await dlg.getByLabel('Dateien durchsuchen').fill('rot');
  assert.equal(await dlg.locator('.files-item').count(), 1);
  await alice.keyboard.press('Escape');
  console.log('✔ Dateien-Ansicht (pro Chat und gesamt, mit Filter und Suche)');

  // Sicherheitsübersicht
  await alice.getByLabel('Sicherheit', { exact: true }).click();
  dlg = alice.getByRole('dialog', { name: 'Sicherheit' });
  await dlg.getByText('Backup-Datei gespeichert').waitFor();
  await dlg.getByText(/Ende-zu-Ende-Verschlüsselung/).waitFor();
  await alice.keyboard.press('Escape');
  await alice.locator('.chat-header .sec-chip', { hasText: /Nicht verifiziert/ }).waitFor();
  console.log('✔ Sicherheitsübersicht und Chat-Status');

  // Neues Gerät → Hinweis auf dem ersten Gerät
  await linkDevice(alice2, P, aliceBackup);
  await alice.locator('.alert-bar', { hasText: /Neues Gerät/ }).waitFor({ timeout: 30000 });
  const lay = await alice.locator('.layout').boundingBox();
  assert.ok(lay.height > 400, 'Layout darf durch Warnbalken nicht zusammenfallen');
  assert.ok(lay.width > 1000, 'volle Seitenbreite');
  await alice.locator('.alert-bar').getByRole('button', { name: 'Ansehen' }).click();
  await alice.getByRole('dialog', { name: 'Sicherheit' }).getByRole('alert').getByText(/Neues Gerät/).waitFor();
  await alice.keyboard.press('Escape');
  await alice.locator('.alert-bar').getByRole('button', { name: 'Hinweis schließen' }).click();
  await alice.locator('.alert-bar').waitFor({ state: 'detached' });
  console.log('✔ Warnung bei neu hinzugefügtem Gerät');
  console.log('MEDIA-E2E BESTANDEN');
} catch (e) {
  console.error('FAIL', e);
  process.exitCode = 1;
} finally {
  await browser?.close();
  server.kill();
}
