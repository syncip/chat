// Bildschirmfotos der wichtigsten Ansichten (hell/dunkel, Desktop/Handy) zur Stil-Kontrolle: node screens.mjs <ausgabeordner>
import { chromium, CHROME, startServer, waitUp, register, sleep, H } from './helpers.mjs';

const OUT = process.argv[2] ?? 'screens';
const P = 18150;
const server = startServer(P);
let browser;
try {
  await waitUp(P);
  browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox', '--no-proxy-server'] });
  for (const scheme of ['light', 'dark']) {
    for (const [dev, vp] of [['desktop', { width: 1280, height: 800 }], ['mobile', { width: 390, height: 800 }]]) {
      const ctx = await browser.newContext({ viewport: vp, colorScheme: scheme });
      const page = await ctx.newPage();
      const shot = async (name) => { await sleep(400); await page.screenshot({ path: `${OUT}/${scheme}-${dev}-${name}.png` }); };
      await page.goto(`http://${H}:${P}/`);
      await shot('1-auth');
      await register(page, P, `u${scheme[0]}${dev[0]}${Date.now() % 100000}`);
      await shot('2-home');
      await page.getByTitle('Einstellungen').click();
      await shot('3-settings');
      await page.getByRole('dialog', { name: 'Einstellungen' }).getByRole('button', { name: /^Sicherheit/ }).click();
      await shot('3b-settings-security');
      await page.keyboard.press('Escape');
      await page.getByTitle('Hinzufügen').click();
      await shot('4-new');
      await page.keyboard.press('Escape');
      await ctx.close();
    }
  }
} finally {
  await browser?.close();
  server.kill();
}
