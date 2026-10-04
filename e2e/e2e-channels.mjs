// Öffentliche Kanäle: Beitritt per Link (offen/Freigabe/PoW/Captcha), Rechte, Timeout, Sperre, Löschen.
import assert from 'node:assert/strict';
import { chromium, CHROME, startServer, waitUp, register, sleep, H } from './helpers.mjs';

const P = 18098;
const server = startServer(P);
let browser;
const addr = (n) => `${n}@${H}:${P}`;

async function createChannel(page, title, mode) {
  await page.getByTitle('Hinzufügen').click();
  await page.getByRole('button', { name: 'Kanal erstellen' }).click();
  const d = page.getByRole('dialog', { name: 'Neuer öffentlicher Kanal' });
  await d.getByLabel('Name').fill(title);
  if (mode) await d.getByLabel('Beitritt').selectOption(mode);
  await d.getByRole('button', { name: 'Kanal erstellen' }).click();
  await page.locator('.chat-header', { hasText: title }).waitFor({ timeout: 20000 });
  await page.getByLabel('Details').click();
  const link = await page.getByRole('dialog', { name: title }).locator('input[readonly]').first().inputValue();
  await page.keyboard.press('Escape');
  assert.match(link, /#\/join\//);
  return link;
}

async function joinChannel(page, link, title, expectError) {
  await page.getByTitle('Hinzufügen').click();
  await page.getByLabel('Link oder Chat-Code').fill(link);
  await page.getByRole('button', { name: 'Weiter' }).click();
  const d = page.getByRole('dialog', { name: 'Kanal beitreten' });
  await d.getByText(title, { exact: true }).waitFor();
  await d.getByRole('button', { name: 'Beitreten' }).click();
  if (expectError) return d;
  await page.locator('.chat-header', { hasText: title }).waitFor({ timeout: 30000 });
}

const post = async (page, text) => {
  const box = page.getByPlaceholder('Beitrag schreiben …');
  await box.fill(text);
  await box.press('Enter');
};
const seen = (page, text) => page.locator('.messages p.text', { hasText: text }).first().waitFor({ timeout: 25000 });

async function memberAction(page, who, fn) {
  await page.getByLabel('Details').click();
  const d = page.locator('[role=dialog]');
  await fn(d.locator('li', { hasText: who }));
  await page.keyboard.press('Escape');
}

try {
  await waitUp(P);
  browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox', '--no-proxy-server'] });
  const mk = async () => (await browser.newContext()).newPage();
  const [alice, bob, carol] = [await mk(), await mk(), await mk()];
  await register(alice, P, 'alice');
  await register(bob, P, 'bob');
  await register(carol, P, 'carol');

  // --- offener Kanal, Mitglieder nur lesen ---
  const link = await createChannel(alice, 'News', 'open');
  assert.ok(!link.includes('?'), 'Schlüssel nur im Fragment');
  await joinChannel(bob, link, 'News');
  await bob.getByText('nur lesen').waitFor();
  await post(alice, 'Hallo Kanal');
  await seen(bob, 'Hallo Kanal');
  console.log('✔ Beitritt per Link, Beitrag wird entschlüsselt, Mitglieder dürfen standardmäßig nur lesen');

  // --- individuelle Rechte ---
  await memberAction(alice, addr('bob'), (li) => li.locator('select').selectOption('write'));
  await bob.getByPlaceholder('Beitrag schreiben …').waitFor({ timeout: 20000 });
  await post(bob, 'Danke!');
  await seen(alice, 'Danke!');
  console.log('✔ individuelles Schreibrecht');

  // --- Timeout ---
  await memberAction(alice, addr('bob'), (li) => li.getByRole('button', { name: '1 Std stumm' }).click());
  await bob.getByText(/stummgeschaltet/).waitFor({ timeout: 20000 });
  console.log('✔ Timeout');

  // --- Löschen durch Moderation ---
  await alice.locator('.msg', { hasText: 'Danke!' }).getByRole('button', { name: 'Löschen' }).click();
  await bob.getByText('Beitrag entfernt').waitFor({ timeout: 20000 });
  console.log('✔ Moderation: Beitrag löschen');

  // --- Sperre ---
  await memberAction(alice, addr('bob'), (li) => li.getByRole('button', { name: 'Sperren', exact: true }).click());
  await bob.getByText(/gesperrt/).first().waitFor({ timeout: 20000 });
  console.log('✔ Sperre');

  // --- Freigabe ---
  const appr = await createChannel(alice, 'Intern', 'approval');
  await joinChannel(carol, appr, 'Intern');
  await carol.getByText(/muss noch von der Moderation freigegeben/).waitFor();
  // Hinweis für die Moderation (ausblendbar)
  const note = alice.locator('.alert-bar', { hasText: /wartet auf Freigabe/ });
  await note.waitFor({ timeout: 30000 });
  await note.getByRole('button', { name: 'Hinweis schließen' }).click();
  await note.waitFor({ state: 'detached' });
  await memberAction(alice, addr('carol'), (li) => li.getByRole('button', { name: 'Freigeben' }).click());
  await carol.getByText('In diesem Kanal darfst du nur lesen.').waitFor({ timeout: 20000 });
  console.log('✔ Beitritt mit Freigabe');

  // --- Proof-of-Work ---
  await alice.getByTitle('Hinzufügen').click();
  await alice.getByRole('button', { name: 'Kanal erstellen' }).click();
  const d = alice.getByRole('dialog', { name: 'Neuer öffentlicher Kanal' });
  await d.getByLabel('Name').fill('Pow');
  await d.getByLabel('Beitritt').selectOption('pow');
  await d.getByLabel(/Schwierigkeit/).fill('10');
  await d.getByRole('button', { name: 'Kanal erstellen' }).click();
  await alice.locator('.chat-header', { hasText: 'Pow' }).waitFor();
  await alice.getByLabel('Details').click();
  const powLink = await alice.getByRole('dialog', { name: 'Pow' }).locator('input[readonly]').first().inputValue();
  await alice.keyboard.press('Escape');
  await joinChannel(carol, powLink, 'Pow');
  console.log('✔ Beitritt mit Proof-of-Work');

  // --- Captcha ---
  const cap = await createChannel(alice, 'Captcha', 'captcha');
  const dc = await joinChannel(bob, cap, 'Captcha', true);
  await dc.getByAltText('Captcha').waitFor({ timeout: 20000 });
  await dc.getByPlaceholder('Zahl eingeben').fill('00000');
  await dc.getByRole('button', { name: 'Absenden' }).click();
  await dc.getByText(/captcha failed|Captcha/i).last().waitFor({ timeout: 20000 });
  console.log('✔ Captcha wird angezeigt, falsche Antwort wird abgelehnt');

  // --- Neustart: Kanäle bleiben nach Sperren/Entsperren erhalten ---
  await alice.reload();
  await alice.getByLabel(/Passphrase/).first().fill('correct horse battery');
  await alice.getByRole('button', { name: /Entsperren/ }).click();
  await alice.locator('.conv', { hasText: 'News' }).click();
  await seen(alice, 'Hallo Kanal');
  console.log('✔ Kanäle überleben Sperren/Entsperren');
  console.log('E2E channels OK');
} catch (e) {
  console.error('FAIL', e);
  process.exitCode = 1;
} finally {
  await browser?.close();
  server.kill();
}
