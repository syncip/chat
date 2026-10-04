/** Benachrichtigungston für eingehende Nachrichten (ein kurzer, per WebAudio erzeugter Doppelton – keine Audiodatei nötig). */
const KEY = 'chat.sound';
export const soundEnabled = (): boolean => { try { return localStorage.getItem(KEY) !== 'off'; } catch { return true; } };
export const setSoundEnabled = (on: boolean): void => { try { localStorage.setItem(KEY, on ? 'on' : 'off'); } catch { /* egal */ } };

let ctx: AudioContext | null = null;
let last = 0;

export function playNotify(): void {
  if (!soundEnabled()) return;
  const now = Date.now();
  if (now - last < 1500) return; // nicht bei jedem Eintrag einer Nachrichtenflut
  last = now;
  try {
    ctx ??= new (window.AudioContext ?? (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext)();
    if (ctx.state === 'suspended') void ctx.resume();
    const t0 = ctx.currentTime;
    [[880, 0], [1320, 0.12]].forEach(([f, d]) => {
      const o = ctx!.createOscillator();
      const g = ctx!.createGain();
      o.type = 'sine';
      o.frequency.value = f;
      g.gain.setValueAtTime(0.0001, t0 + d);
      g.gain.exponentialRampToValueAtTime(0.18, t0 + d + 0.02);
      g.gain.exponentialRampToValueAtTime(0.0001, t0 + d + 0.22);
      o.connect(g).connect(ctx!.destination);
      o.start(t0 + d);
      o.stop(t0 + d + 0.25);
    });
  } catch {
    /* Audio nicht verfügbar oder noch nicht freigegeben */
  }
}
