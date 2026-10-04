// Gruppen-E2E: Alice (Server A), Carol (Server A), Bob (Server B). Alice erstellt eine Gruppe mit Bob und Carol.
import assert from 'node:assert/strict';
import { H, chromium, CHROME, startServer, waitUp, register, contactLink, connect, send, seen, sleep } from './helpers.mjs';

const servers = [startServer(18090), startServer(18091)];
let browser;
try {
  await Promise.all([waitUp(18090), waitUp(18091)]);
  browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox', '--no-proxy-server'] });
  const mk = async () => (await browser.newContext()).newPage();
  const [alice, bob, carol] = [await mk(), await mk(), await mk()];
  await register(alice, 18090, 'alice');
  await register(carol, 18090, 'carol');
  await register(bob, 18091, 'bob');

  const la = await contactLink(alice);
  await connect(bob, alice, la, `bob@${H}:18091`);
  await connect(carol, alice, la, `carol@${H}:18090`);
  console.log('✔ Alice ist mit Bob (föderiert) und Carol verbunden');

  // Gruppe erstellen
  await alice.getByTitle('Hinzufügen').click();
  await alice.getByRole('button', { name: 'Gruppe erstellen' }).click();
  await alice.getByLabel('Name').fill('Projekt X');
  await alice.getByLabel(`bob@${H}:18091`).check();
  await alice.getByLabel(`carol@${H}:18090`).check();
  await alice.getByRole('button', { name: 'Gruppe erstellen' }).click();
  await alice.locator('.chat-header strong', { hasText: 'Projekt X' }).waitFor();

  for (const p of [bob, carol]) {
    await p.locator('.requests .conv', { hasText: /Gruppe|Projekt/ }).click();
    await p.getByRole('button', { name: 'Annehmen' }).click();
  }
  console.log('✔ Gruppe erstellt, Mitglieder haben angenommen');

  // Nachrichten in alle Richtungen (Bob ↔ Carol benötigen das Postfach-Verzeichnis)
  await send(alice, 'hallo gruppe');
  await seen(bob, 'hallo gruppe'); await seen(carol, 'hallo gruppe');
  await sleep(2500); // Verzeichnis-Nachrichten verteilen
  await send(bob, 'bob an alle');
  await seen(alice, 'bob an alle'); await seen(carol, 'bob an alle');
  await send(carol, 'carol an alle');
  await seen(alice, 'carol an alle'); await seen(bob, 'carol an alle');
  console.log('✔ Gruppennachrichten Alice/Bob/Carol in alle Richtungen (zwei Server)');

  // Titel wurde übertragen
  assert.ok(await bob.locator('.conv .title', { hasText: 'Projekt X' }).count() > 0, 'Gruppenname bei Bob');

  // Umbenennen mit Speichern-Button
  await alice.getByLabel('Details').click();
  const rn = alice.getByRole('dialog');
  await rn.getByLabel('Gruppenname').fill('Projekt Y');
  await rn.getByText('Ungespeicherte Änderungen').waitFor();
  await rn.getByRole('button', { name: 'Speichern' }).click();
  await rn.getByText('Gespeichert').waitFor();
  await alice.keyboard.press('Escape');
  await bob.locator('.conv .title', { hasText: 'Projekt Y' }).waitFor({ timeout: 20000 });
  console.log('✔ Gruppe umbenennen mit Speichern-Button');

  // Mitglied entfernen: danach erhält Carol nichts mehr
  await alice.getByLabel('Details').click();
  await alice.locator('li', { hasText: `carol@${H}:18090` }).getByRole('button', { name: 'Entfernen' }).click();
  await sleep(1500);
  await alice.keyboard.press('Escape');
  await send(alice, 'geheim nach Entfernung');
  await seen(bob, 'geheim nach Entfernung');
  await sleep(3000);
  assert.equal(await carol.getByText('geheim nach Entfernung').count(), 0, 'entferntes Mitglied darf nichts mehr lesen');
  console.log('✔ Entferntes Mitglied kann nicht mehr mitlesen');
  console.log('\nGRUPPEN-E2E BESTANDEN');
} finally {
  await browser?.close();
  servers.forEach((s) => s.kill());
}
