// Bestätigungen (gesendet/empfangen/gelesen), Einmal-Nachrichten, einklappbarer Code, mit Einstellungen pro Nutzer.
import assert from 'node:assert/strict';
import { chromium, CHROME, startServer, waitUp, register, contactLink, connect, send, seen, sleep, H } from './helpers.mjs';

const server = startServer(18097);
let browser;
const setting = async (page, label, on) => {
  await page.getByTitle('Einstellungen').click();
  const box = page.getByLabel(label);
  if ((await box.isChecked()) !== on) await box.setChecked(on);
  await page.keyboard.press('Escape');
};
try {
  await waitUp(18097);
  browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox', '--no-proxy-server'] });
  const mk = async () => (await browser.newContext()).newPage();
  const [alice, bob] = [await mk(), await mk()];
  await register(alice, 18097, 'alice');
  await register(bob, 18097, 'bob');
  await connect(bob, alice, await contactLink(alice), `bob@${H}:18097`);
  const hasStatus = (p, t) => p.locator('.msg.mine .meta', { hasText: t }).last().waitFor({ timeout: 20000 });

  // Standard: nur „gesendet“
  await send(bob, 'erste');
  await seen(alice, 'erste');
  await hasStatus(bob, '✓ gesendet');
  await sleep(1500);
  assert.equal(await bob.locator('.msg.mine .meta', { hasText: 'empfangen' }).count(), 0, 'ohne Einstellung keine Empfangsbestätigung');
  console.log('✔ Standard: nur „gesendet“, keine Bestätigungen');

  // Alice sendet „empfangen“, Bob sieht es nur, wenn er es selbst auch eingeschaltet hat
  await setting(alice, /„Empfangen“ an/, true);
  await send(bob, 'zweite');
  await seen(alice, 'zweite');
  await sleep(1500);
  assert.equal(await bob.locator('.msg.mine .meta', { hasText: 'empfangen' }).count(), 0, 'Bob hat es selbst aus: sieht es nicht');
  await setting(bob, /„Empfangen“ an/, true);
  await send(bob, 'dritte');
  await seen(alice, 'dritte');
  await hasStatus(bob, '✓✓ empfangen');
  console.log('✔ „Empfangen“ nur sichtbar, wenn beide es eingeschaltet haben (Gegenseitigkeit)');

  // „Gelesen“
  await setting(alice, /„Gelesen“ senden/, true);
  await setting(bob, /„Gelesen“ senden/, true);
  await send(bob, 'vierte');
  await seen(alice, 'vierte');
  await hasStatus(bob, '✓✓ gelesen');
  console.log('✔ „Gelesen“');

  // Einmal-Nachricht: Bob → Alice
  await bob.getByTitle(/Einmal-Nachricht/).click();
  await send(bob, 'Kennwort: hunter2');
  const row = alice.locator('.msg', { hasText: 'Einmal-Nachricht anzeigen' });
  await row.waitFor({ timeout: 20000 });
  assert.equal(await alice.getByText('hunter2').count(), 0, 'Inhalt darf vor dem Anzeigen nicht sichtbar sein');
  await row.getByRole('button', { name: /anzeigen/ }).click();
  await alice.getByRole('dialog').getByText('hunter2').waitFor();
  await alice.getByRole('button', { name: 'Schließen und löschen' }).click();
  await alice.locator('.msg', { hasText: 'Einmal-Nachricht gelesen' }).waitFor();
  assert.equal(await alice.getByText('hunter2').count(), 0, 'nach dem Schließen gelöscht');
  await bob.locator('.msg.mine', { hasText: 'Einmal-Nachricht' }).locator('.meta', { hasText: 'gelesen' }).waitFor({ timeout: 20000 });
  console.log('✔ Einmal-Nachricht: verdeckt, einmal sichtbar, danach gelöscht, Absender sieht „gelesen“');

  // Einmal-Nachrichten gibt es nur in 1:1: nach Sperren/Entsperren bleibt sie gelöscht
  await alice.getByTitle('Einstellungen').click();
  await alice.getByRole('button', { name: 'Sperren' }).click();
  await alice.getByLabel('Passphrase', { exact: true }).fill('correct horse battery');
  await alice.getByRole('button', { name: 'Entsperren' }).click();
  await alice.locator('.conv').first().click();
  await alice.locator('.msg', { hasText: 'Einmal-Nachricht gelesen' }).waitFor({ timeout: 30000 });
  assert.equal(await alice.getByText('hunter2').count(), 0);
  console.log('✔ Gelöscht bleibt gelöscht (auch nach Neustart der Sitzung)');

  // Einklappbarer Code
  const long = Array.from({ length: 25 }, (_, i) => `line ${i + 1}`).join('\n');
  await alice.getByTitle('Codeblock').click();
  await alice.getByPlaceholder('Code …').fill(long);
  await alice.getByRole('button', { name: 'Senden' }).click();
  const box = bob.locator('details.codebox').last();
  await box.waitFor({ timeout: 20000 });
  assert.equal(await box.evaluate((d) => d.open), false, 'langer Code startet eingeklappt');
  await box.locator('summary').click();
  assert.equal(await box.evaluate((d) => d.open), true);
  await box.getByText('line 25', { exact: false }).waitFor();
  await box.locator('summary').click();
  assert.equal(await box.evaluate((d) => d.open), false);
  console.log('✔ Code ein- und ausklappbar (lange Blöcke starten eingeklappt)');
  console.log('\nFEATURES-E2E BESTANDEN');
} finally {
  await browser?.close();
  server.kill();
}
