// Passkey-Entsperren (WebAuthn + PRF) mit virtuellem Authenticator.
import assert from 'node:assert/strict';
import { chromium, CHROME, startServer, waitUp, register, H, PASS , openSettings } from './helpers.mjs';

const P = 18107;
const server = startServer(P);
let browser;
try {
  await waitUp(P);
  browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox', '--no-proxy-server'] });
  const page = await (await browser.newContext()).newPage();
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('WebAuthn.enable');
  await cdp.send('WebAuthn.addVirtualAuthenticator', { options: { protocol: 'ctap2', transport: 'internal', hasResidentKey: true, hasUserVerification: true, isUserVerified: true, automaticPresenceSimulation: true, hasPrf: true } });
  await page.goto(`http://${H}:${P}/`);
  await register(page, P, 'anna');
  const dlg = await openSettings(page, 'Sicherheit');
  await dlg.getByLabel('Passphrase für Passkey').fill('falsch falsch');
  await dlg.getByRole('button', { name: 'Passkey einrichten' }).click();
  await dlg.getByText('Passphrase ist falsch.').waitFor();
  await dlg.getByLabel('Passphrase für Passkey').fill(PASS);
  await dlg.getByRole('button', { name: 'Passkey einrichten' }).click();
  await dlg.getByRole('button', { name: 'Passkey entfernen' }).waitFor({ timeout: 20000 });
  console.log('✔ Passkey eingerichtet');
  await dlg.getByRole('button', { name: 'Jetzt sperren' }).click();
  await page.getByRole('button', { name: /Mit Passkey entsperren/ }).click();
  await page.getByText('● verbunden').waitFor({ timeout: 30000 });
  console.log('✔ Entsperren per Passkey');
  console.log('PASSKEY-E2E BESTANDEN');
} catch (e) {
  console.error('FAIL', e);
  process.exitCode = 1;
} finally {
  await browser?.close();
  server.kill();
}
