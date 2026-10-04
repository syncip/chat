// Kanal nachträglich von privat auf öffentlich und zurück stellen; Mitglieder bekommen den neuen Schlüssel per Link.
import { chromium, CHROME, startServer, waitUp, register } from './helpers.mjs';

const P = 18111;
const server = startServer(P);
let browser;
const post = async (page, text) => { const b = page.getByPlaceholder('Beitrag schreiben …'); await b.fill(text); await b.press('Enter'); };
try {
  await waitUp(P);
  browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox', '--no-proxy-server'] });
  const mk = async () => (await browser.newContext()).newPage();
  const [alice, bob] = [await mk(), await mk()];
  await register(alice, P, 'alice');
  await register(bob, P, 'bob');

  await alice.getByTitle('Hinzufügen').click();
  await alice.getByRole('button', { name: 'Kanal erstellen' }).click();
  const d = alice.getByRole('dialog', { name: 'Neuer öffentlicher Kanal' });
  await d.getByLabel('Name').fill('Wechsel');
  await d.getByRole('button', { name: 'Kanal erstellen' }).click();
  await alice.locator('.chat-header', { hasText: 'Wechsel' }).waitFor({ timeout: 20000 });
  await post(alice, 'privat-alt');
  await alice.getByLabel('Details').click();
  let info = alice.getByRole('dialog', { name: 'Wechsel' });
  const link1 = await info.locator('input[readonly]').first().inputValue();
  await alice.keyboard.press('Escape');

  await bob.getByTitle('Hinzufügen').click();
  await bob.getByLabel('Link oder Chat-Code').fill(link1);
  await bob.getByRole('button', { name: 'Weiter' }).click();
  const jd = bob.getByRole('dialog', { name: 'Kanal beitreten' });
  await jd.getByRole('button', { name: 'Beitreten' }).click();
  await bob.locator('.chat-header', { hasText: 'Wechsel' }).waitFor({ timeout: 30000 });
  await bob.locator('.messages').getByText('privat-alt').waitFor({ timeout: 20000 });

  // privat → öffentlich
  await alice.getByLabel('Details').click();
  info = alice.getByRole('dialog', { name: 'Wechsel' });
  await info.getByLabel('Sichtbarkeit').selectOption('public');
  await info.getByRole('button', { name: 'Speichern' }).click();
  await info.getByText('✔ Gespeichert').waitFor({ timeout: 15000 });
  await alice.keyboard.press('Escape');
  await post(alice, 'oeffentlich-neu');
  await bob.locator('.messages').getByText('oeffentlich-neu').waitFor({ timeout: 25000 });
  await bob.locator('.messages').getByText('privat-alt').waitFor();
  console.log('✔ privat → öffentlich: Mitglied liest neue und alte Beiträge');

  // öffentlich → privat: Mitglied braucht neuen Link
  await alice.getByLabel('Details').click();
  info = alice.getByRole('dialog', { name: 'Wechsel' });
  await info.getByLabel('Sichtbarkeit').selectOption('private');
  await info.getByRole('button', { name: 'Speichern' }).click();
  await info.getByText('✔ Gespeichert').waitFor({ timeout: 15000 });
  const link2 = await info.locator('input[readonly]').first().inputValue();
  await alice.keyboard.press('Escape');
  await bob.getByLabel('Neuer Einladungslink').waitFor({ timeout: 25000 });
  await bob.getByLabel('Neuer Einladungslink').fill(link2);
  await bob.getByRole('button', { name: 'Schlüssel übernehmen' }).click();
  await post(alice, 'privat-neu');
  await bob.locator('.messages').getByText('privat-neu').waitFor({ timeout: 25000 });
  await bob.locator('.messages').getByText('oeffentlich-neu').waitFor();
  console.log('✔ öffentlich → privat: neuer Schlüssel per Link, Verlauf bleibt lesbar');
  console.log('VISIBILITY-E2E BESTANDEN');
} catch (e) {
  console.error('FAIL', e);
  process.exitCode = 1;
} finally {
  await browser?.close();
  server.kill();
}
