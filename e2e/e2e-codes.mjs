// Chat-Code („martinistcool“) und Kontakt-QR für neue Chats sowie QR-Anmeldung eines weiteren Geräts.
import assert from 'node:assert/strict';
import { chromium, CHROME, startServer, waitUp, register, sleep, H } from './helpers.mjs';

const P = 18108;
const server = startServer(P);
let browser;
try {
  await waitUp(P);
  browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox', '--no-proxy-server'] });
  const mk = async () => (await browser.newContext()).newPage();
  const [alice, bob] = [await mk(), await mk()];
  await register(alice, P, 'martin');
  await register(bob, P, 'bob');

  await alice.getByTitle('Einstellungen').click();
  let dlg = alice.getByRole('dialog', { name: 'Einstellungen' });
  await dlg.getByLabel('Chat-Code').fill('MartinIstCool');
  await dlg.getByRole('button', { name: 'Code speichern' }).click();
  await dlg.getByText('Chat-Code „martinistcool“ gespeichert.').waitFor();
  await dlg.getByRole('button', { name: 'Meinen Kontakt-QR-Code anzeigen' }).click();
  await dlg.getByRole('img', { name: 'QR-Code deines Kontaktlinks' }).locator('svg').waitFor();
  console.log('✔ Chat-Code gespeichert, Kontakt-QR angezeigt');
  await dlg.getByRole('button', { name: 'QR-Code anzeigen' }).click(); // Geräte-QR
  await dlg.getByRole('img', { name: 'QR-Code zum Anmelden eines Geräts' }).locator('svg').waitFor({ timeout: 15000 });
  console.log('✔ Geräte-QR-Code erzeugt');
  await alice.keyboard.press('Escape');

  await bob.getByTitle('Hinzufügen').click();
  await bob.getByLabel('Link oder Chat-Code').fill('gibtsnicht');
  await bob.getByRole('button', { name: 'Weiter' }).click();
  await bob.getByText('Diesen Code gibt es nicht.').waitFor();
  await bob.getByLabel('Link oder Chat-Code').fill('martinistcool');
  await bob.getByRole('button', { name: 'Weiter' }).click();
  await bob.locator('.chat-header').waitFor({ timeout: 30000 });
  await alice.getByText('Anfragen').waitFor({ timeout: 20000 });
  console.log('✔ Chat per Chat-Code gestartet');
  console.log('CODES-E2E BESTANDEN');
} catch (e) {
  console.error('FAIL', e);
  process.exitCode = 1;
} finally {
  await browser?.close();
  server.kill();
}
