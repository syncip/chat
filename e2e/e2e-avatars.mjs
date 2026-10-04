// Profil-, Gruppen- und Kanalbilder.
import { chromium, CHROME, startServer, waitUp, register, contactLink, connect, H , openSettings } from './helpers.mjs';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);

const P = 18110;
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

const file = () => ({ name: 'bild.png', mimeType: 'image/png', buffer: png() });
try {
  await waitUp(P);
  browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox', '--no-proxy-server'] });
  const mk = async () => (await browser.newContext()).newPage();
  const [alice, bob] = [await mk(), await mk()];
  await register(alice, P, 'alice');
  await register(bob, P, 'bob');
  await connect(bob, alice, await contactLink(alice), `bob@${H}:${P}`);

  // Profilbild: Alice setzt es, Bob sieht es in der Chatliste
  const sd = await openSettings(alice, 'Profil');
  await sd.getByLabel('Profilbild wählen').setInputFiles(file());
  await sd.getByText('Profilbild gesetzt.').waitFor({ timeout: 15000 });
  await alice.keyboard.press('Escape');
  await bob.locator('.conv img.avatar').first().waitFor({ timeout: 25000 });
  console.log('✔ Profilbild kommt beim Chat-Partner an');

  // Kanalbild
  await alice.getByTitle('Hinzufügen').click();
  await alice.getByRole('button', { name: 'Kanal erstellen' }).click();
  const d = alice.getByRole('dialog', { name: 'Neuer öffentlicher Kanal' });
  await d.getByLabel('Name').fill('Fotos');
  await d.getByRole('button', { name: 'Kanal erstellen' }).click();
  await alice.locator('.chat-header', { hasText: 'Fotos' }).waitFor({ timeout: 20000 });
  await alice.getByLabel('Details').click();
  const cd = alice.getByRole('dialog', { name: 'Fotos' });
  await cd.getByLabel('Kanalbild wählen').setInputFiles(file());
  await cd.getByRole('button', { name: 'Bild entfernen' }).waitFor({ timeout: 15000 });
  await alice.keyboard.press('Escape');
  await alice.locator('.chat-header img.avatar').waitFor({ timeout: 15000 });
  await alice.locator('.convs .conv', { hasText: 'Fotos' }).locator('img.avatar').waitFor();
  console.log('✔ Kanalbild gesetzt');
  console.log('AVATARS-E2E BESTANDEN');
} catch (e) {
  console.error('FAIL', e);
  process.exitCode = 1;
} finally {
  await browser?.close();
  server.kill();
}
