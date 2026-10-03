// Multi-Device: zweites Gerät per Backup-Datei, Chats erscheinen automatisch, beide Geräte lesen/schreiben, Widerruf.
import assert from 'node:assert/strict';
import { chromium, CHROME, startServer, waitUp, register, linkDevice, contactLink, connect, send, seen, sleep, H } from './helpers.mjs';

const server = startServer(18099);
let browser;
try {
  await waitUp(18099);
  browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox', '--no-proxy-server'] });
  const mk = async (n) => { const p = await (await browser.newContext()).newPage(); p.on('pageerror', (e) => console.error(`[${n}] pageerror`, e.message)); return p; };
  const [a1, bob, a2] = [await mk('a1'), await mk('bob'), await mk('a2')];
  const backup = await register(a1, 18099, 'alice');
  await register(bob, 18099, 'bob');
  await connect(bob, a1, await contactLink(a1), `bob@${H}:18099`);
  await send(bob, 'hallo vor dem zweiten geraet');
  await seen(a1, 'hallo vor dem zweiten geraet');
  console.log('✔ Alice (Gerät 1) und Bob chatten');

  // Gerät 2 anmelden: Backup-Datei + Passphrase
  await linkDevice(a2, 18099, backup);
  console.log('✔ Gerät 2 per Backup-Datei angemeldet');

  // Gerät 1 nimmt Gerät 2 in den Chat auf: die Unterhaltung erscheint ohne Zutun
  await a2.locator('.conv .title', { hasText: `bob@${H}:18099` }).waitFor({ timeout: 40000 });
  await a2.locator('.conv', { hasText: `bob@${H}:18099` }).click();
  console.log('✔ Gerät 2 wurde automatisch in die Unterhaltung aufgenommen');
  assert.equal(await a2.getByText('hallo vor dem zweiten geraet').count(), 0, 'Verlauf vor dem Beitritt ist nicht lesbar (Forward Secrecy)');

  // Gerät 2 muss sein Postfach angekündigt haben; Bob schreibt → beide Geräte lesen
  await sleep(2500);
  await send(bob, 'an beide geraete');
  await seen(a1, 'an beide geraete');
  await seen(a2, 'an beide geraete');
  console.log('✔ Nachricht von Bob erreicht beide Geräte');

  // Gerät 2 schreibt → Bob und Gerät 1 sehen es
  await send(a2, 'antwort von geraet 2');
  await seen(bob, 'antwort von geraet 2');
  await a1.locator('.conv').first().click();
  await a1.locator('.msg.mine p.text', { hasText: 'antwort von geraet 2' }).waitFor({ timeout: 20000 });
  console.log('✔ Gerät 2 → Bob; Gerät 1 sieht die eigene Nachricht von Gerät 2');

  // Gerät 1 schreibt → Bob und Gerät 2
  await send(a1, 'antwort von geraet 1');
  await seen(bob, 'antwort von geraet 1');
  await a2.locator('.msg.mine p.text', { hasText: 'antwort von geraet 1' }).waitFor({ timeout: 20000 });
  console.log('✔ Gerät 1 → Bob; Gerät 2 sieht sie');

  // Geräteliste und Widerruf von Gerät 1 aus
  await a1.getByTitle('Einstellungen').click();
  await a1.getByText('(dieses Gerät)').waitFor();
  assert.equal(await a1.locator('li', { hasText: 'seit' }).count(), 2);
  a1.once('dialog', (d) => d.accept());
  await a1.getByRole('button', { name: 'widerrufen' }).click();
  await a1.getByText('Gerät widerrufen.').waitFor({ timeout: 15000 });
  await a1.keyboard.press('Escape');
  await a2.getByText('○ offline').waitFor({ timeout: 20000 });
  console.log('✔ Widerrufenes Gerät wird getrennt');
  await sleep(3000);
  await send(bob, 'nur noch geraet 1');
  await seen(a1, 'nur noch geraet 1');
  await sleep(2500);
  assert.equal(await a2.getByText('nur noch geraet 1').count(), 0, 'widerrufenes Gerät erhält nichts mehr');
  console.log('✔ Nach Widerruf liest nur noch Gerät 1 mit');
  console.log('\nMULTIDEVICE-E2E BESTANDEN');
} finally {
  await browser?.close();
  server.kill();
}
