// Admin, öffentliche Kanäle ohne Konto, ntfy-kompatible Webhooks, Konto-Sync zwischen Geräten, Angemeldet bleiben / Inaktivitäts-Sperre, Mindestlänge.
import assert from 'node:assert/strict';
import { chromium, CHROME, startServer, waitUp, register, linkDevice, sleep, H, PASS } from './helpers.mjs';

const P = 18104;
const server = startServer(P);
let browser;
const base = `http://${H}:${P}`;

async function createChannel(page, title, { pub = false, mode = 'open' } = {}) {
  await page.getByTitle('Hinzufügen').click();
  await page.getByRole('button', { name: 'Kanal erstellen' }).click();
  const d = page.getByRole('dialog', { name: 'Neuer öffentlicher Kanal' });
  await d.getByLabel('Name').fill(title);
  await d.getByLabel('Beitritt').selectOption(mode);
  if (pub) {
    await d.getByLabel(/Öffentlich sichtbar/).check();
    await d.getByText(/unverschlüsselt und für jeden im Internet lesbar/).waitFor();
  }
  await d.getByRole('button', { name: 'Kanal erstellen' }).click();
  await page.locator('.chat-header', { hasText: title }).waitFor({ timeout: 20000 });
}
const post = async (page, text) => { const b = page.getByPlaceholder('Beitrag schreiben …'); await b.fill(text); await b.press('Enter'); };

try {
  await waitUp(P);
  browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox', '--no-proxy-server'] });
  const mk = async () => (await browser.newContext()).newPage();
  const [alice, bob, anon, alice2] = [await mk(), await mk(), await mk(), await mk()];

  // --- Mindestlänge einstellbar ---
  await alice.goto(`${base}/`);
  await alice.evaluate(() => localStorage.setItem('chat.minPass', '4'));
  const aliceBackup = await register(alice, P, 'martin', 'abcd'); // erster Nutzer = Admin, 4-Zeichen-Passphrase
  await register(bob, P, 'bob');
  console.log('✔ Passphrase-Mindestlänge einstellbar (4 Zeichen akzeptiert)');

  // --- Admin ---
  await alice.getByLabel('Administration').click();
  let dlg = alice.getByRole('dialog', { name: 'Server-Administration' });
  await dlg.getByText('Nutzer', { exact: true }).waitFor({ timeout: 15000 });
  assert.equal(await bob.getByLabel('Administration').count(), 0, 'zweiter Nutzer ist kein Admin');
  await dlg.getByRole('tab', { name: 'Einstellungen' }).click();
  await dlg.getByLabel(/^Registrierung/).selectOption('closed');
  await dlg.getByRole('button', { name: 'Speichern' }).click();
  await dlg.getByText('Gespeichert und aktiv').waitFor();
  assert.equal((await (await fetch(`${base}/v1/server-info`)).json()).registration, 'closed');
  await dlg.getByLabel(/^Registrierung/).selectOption('invite');
  await dlg.getByLabel(/^Mindestlänge der Passphrase/).fill('6');
  await dlg.getByRole('button', { name: 'Speichern' }).click();
  await dlg.getByText('Gespeichert und aktiv').waitFor();
  assert.equal((await (await fetch(`${base}/v1/server-info`)).json()).min_passphrase, 6, 'Admin legt Mindestlänge fest');
  await dlg.getByRole('tab', { name: 'Nutzer' }).click();
  await dlg.getByText('bob').waitFor();
  await dlg.getByLabel('Nutzer suchen').fill('BO');
  await dlg.getByRole('button', { name: 'Suchen' }).click();
  await dlg.getByRole('button', { name: 'Sperren / Limit …' }).click();
  const rd = alice.getByRole('dialog', { name: 'Sperre für bob' });
  await rd.getByLabel('Sperre-Art').selectOption('temp');
  await rd.getByLabel('Dauer', { exact: true }).fill('2');
  await rd.getByLabel('Einheit').selectOption('1440');
  await rd.getByRole('button', { name: 'Speichern' }).click();
  await dlg.getByText(/Gesperrt bis/).waitFor();
  await dlg.getByRole('button', { name: 'Sperren / Limit …' }).click();
  await rd.getByLabel('Sperre-Art').selectOption('');
  await rd.getByRole('button', { name: 'Speichern' }).click();
  await dlg.getByText(/Gesperrt bis/).waitFor({ state: 'detached' });
  await alice.keyboard.press('Escape');
  console.log('✔ Admin (erster Nutzer): Statistik, Einstellungen speichern, Nutzerliste');

  // --- Öffentlicher Kanal ohne Konto ---
  await createChannel(alice, 'Wetter', { pub: true });
  await alice.getByText(/Öffentlicher Kanal: Inhalte sind unverschlüsselt/).waitFor();
  await post(alice, 'Hallo Welt');
  await alice.getByLabel('Details').click();
  dlg = alice.getByRole('dialog', { name: 'Wetter' });
  const pubLink = await dlg.getByLabel(/Öffentlicher Lese-Link/).inputValue();
  assert.match(pubLink, /#\/c\//);
  await anon.goto(pubLink.replace(/^https?:\/\/[^/]+/, base));
  await anon.getByText(/alle Inhalte sind unverschlüsselt und für jeden sichtbar/).waitFor({ timeout: 20000 });
  await anon.locator('.messages p.text', { hasText: 'Hallo Welt' }).waitFor({ timeout: 20000 });
  assert.equal(await anon.getByPlaceholder('Beitrag schreiben …').count(), 0);
  console.log('✔ Öffentlicher Kanal ist ohne Konto lesbar (mit Warnhinweis)');

  // --- Webhook (ntfy-kompatibel) am öffentlichen Kanal ---
  const addHook = async (name) => {
    await dlg.getByPlaceholder(/Name, z\. B\./).fill(name);
    await dlg.getByRole('button', { name: 'Webhook anlegen' }).click();
    const url = await dlg.locator('.banner.warn input[readonly]').inputValue();
    assert.match(url, /\/h\//);
    return url.replace(/^https?:\/\/[^/]+/, base);
  };
  const hook = await addHook('Monitoring');
  const r = await fetch(hook, { method: 'POST', body: 'Backup fertig', headers: { Title: 'Nachtlauf', Priority: 'high', Tags: 'white_check_mark' } });
  assert.equal(r.status, 200);
  assert.equal((await r.json()).event, 'message');
  await anon.locator('.messages p.text', { hasText: 'Backup fertig' }).waitFor({ timeout: 20000 });
  await anon.getByText(/🔔 Monitoring/).first().waitFor();
  console.log('✔ Webhook: ntfy-Aufruf erscheint live im Kanal');
  await dlg.getByRole('button', { name: 'Löschen' }).first().click().catch(() => {});
  alice.once('dialog', (d) => d.accept());
  await dlg.locator('.row', { hasText: 'Monitoring' }).getByRole('button', { name: 'Löschen' }).click();
  await sleep(800);
  assert.equal((await fetch(hook, { method: 'POST', body: 'x' })).status, 404, 'gelöschter Webhook ist wertlos');
  console.log('✔ Webhook löschen');
  await alice.keyboard.press('Escape');

  // --- Webhook an privatem (verschlüsseltem) Kanal ---
  await createChannel(alice, 'Intern');
  await alice.getByLabel('Details').click();
  dlg = alice.getByRole('dialog', { name: 'Intern' });
  const hook2 = await addHook('Router');
  assert.equal((await fetch(hook2 + '/publish?message=Link+down&title=WAN&tags=warning')).status, 200);
  await alice.keyboard.press('Escape');
  await alice.locator('.messages p.text', { hasText: 'Link down' }).waitFor({ timeout: 20000 });
  console.log('✔ Webhook an verschlüsseltem Kanal (Server verschlüsselt, Client entschlüsselt)');

  // --- Konto-Sync: zweites Gerät übernimmt Kanäle ---
  await sleep(3000); // Gerät 1 gibt Änderungen entprellt weiter
  await alice2.goto(`${base}/`);
  await alice2.evaluate(() => localStorage.setItem('chat.minPass', '4'));
  await linkDevice(alice2, P, aliceBackup, 'abcdef');
  await alice2.locator('.conv', { hasText: 'Wetter' }).waitFor({ timeout: 30000 });
  await alice2.locator('.conv', { hasText: 'Intern' }).waitFor({ timeout: 30000 });
  console.log('✔ Sync: Kanäle erscheinen auf dem zweiten Gerät');
  await createChannel(alice2, 'Neu');
  await alice.locator('.conv', { hasText: 'Neu' }).waitFor({ timeout: 30000 });
  console.log('✔ Sync: auf Gerät 2 erstellter Kanal erscheint auf Gerät 1');
  // Einstellung
  await alice.getByTitle('Einstellungen').click();
  await alice.getByLabel(/„Gelesen“ senden/).check();
  await alice.keyboard.press('Escape');
  await alice2.waitForFunction(() => true);
  await sleep(4000);
  await alice2.getByTitle('Einstellungen').click();
  await alice2.getByLabel(/„Gelesen“ senden/).waitFor();
  assert.ok(await alice2.getByLabel(/„Gelesen“ senden/).isChecked(), 'Einstellung wurde synchronisiert');
  await alice2.keyboard.press('Escape');
  console.log('✔ Sync: Einstellungen');
  // Löschen auf Gerät 1 → verschwindet auf Gerät 2
  await alice.locator('.conv', { hasText: 'Neu' }).click();
  await alice.getByLabel('Details').click();
  alice.once('dialog', (d) => d.accept());
  await alice.getByRole('button', { name: 'Kanal löschen' }).click();
  await alice2.locator('.conv', { hasText: 'Neu' }).waitFor({ state: 'detached', timeout: 30000 });
  console.log('✔ Sync: Löschung');

  // --- Angemeldet bleiben + Inaktivitäts-Sperre ---
  await alice.getByTitle('Einstellungen').click();
  await alice.getByRole('button', { name: 'Jetzt sperren' }).click();
  await alice.getByLabel('Passphrase', { exact: true }).waitFor();
  await alice.getByLabel(/Angemeldet bleiben/).selectOption('tab');
  await alice.getByLabel('Passphrase', { exact: true }).fill('abcd');
  await alice.getByRole('button', { name: 'Entsperren' }).click();
  await alice.getByText('● verbunden').waitFor({ timeout: 30000 });
  await alice.reload();
  await alice.getByText('● verbunden').waitFor({ timeout: 30000 });
  console.log('✔ Angemeldet bleiben: Neuladen ohne erneute Passphrase');
  await alice.evaluate(() => localStorage.setItem('chat.idleLock', '0.05'));
  await alice.getByLabel('Passphrase', { exact: true }).waitFor({ timeout: 40000 }); // Inaktivitäts-Sperre greift nach ca. 3 s (Prüfintervall 10 s)
  await alice.reload();
  await alice.getByLabel('Passphrase', { exact: true }).waitFor();
  console.log('✔ Inaktivitäts-Sperre löscht die gemerkte Sitzung');
  console.log('ACCOUNT-E2E BESTANDEN');
} catch (e) {
  console.error('FAIL', e);
  process.exitCode = 1;
} finally {
  await browser?.close();
  server.kill();
}
