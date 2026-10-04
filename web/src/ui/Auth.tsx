import { useRef, useState } from 'react';
import { isInsecureTransport } from '../lib/util';
import { useEngine } from './hooks';
import { minPassLength, setMinPassLength, rememberMode, setRememberMode, REMEMBER_LABEL, type RememberMode } from '../lib/prefs';
import { saveSession, persistentAvailable } from '../lib/session';

function TransportNote() {
  return isInsecureTransport() ? (
    <p className="warn">⚠ Unverschlüsselte Verbindung (http). Nachrichten bleiben Ende-zu-Ende verschlüsselt, aber jemand im Netzwerk kann diese App manipulieren. Nur in vertrauenswürdigen Netzen nutzen.</p>
  ) : null;
}

export function Onboarding() {
  const e = useEngine();
  const [server, setServer] = useState(location.host);
  const [name, setName] = useState('');
  const [invite, setInvite] = useState('');
  const [pass, setPass] = useState('');
  const [pass2, setPass2] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const file = useRef<HTMLInputElement>(null);
  const [restore, setRestore] = useState(false);
  const [bpass, setBpass] = useState('');
  const [minLen, setMinLen] = useState(minPassLength());

  async function submit(ev: React.FormEvent) {
    ev.preventDefault();
    setErr('');
    if (pass.length < minLen) return setErr(`Die Passphrase braucht mindestens ${minLen} Zeichen.`);
    if (pass !== pass2) return setErr('Die Passphrasen stimmen nicht überein.');
    setBusy(true);
    try {
      if (restore) {
        const f = file.current?.files?.[0];
        if (!f) throw new Error('Bitte Backup-Datei wählen.');
        await e.linkDevice(new Uint8Array(await f.arrayBuffer()), bpass, pass);
      } else {
        await e.createAccount({ server, name, invite, passphrase: pass });
      }
    } catch (x) {
      setErr((x as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="center">
      <form className="card auth" onSubmit={submit}>
        <h1>Chat</h1>
        <TransportNote />
        <p className="muted">
          Ende-zu-Ende-verschlüsselt. Dein Schlüssel wird auf diesem Gerät erzeugt und verlässt es nie.
        </p>
        {!restore ? (
          <>
            <label>Server<input value={server} onChange={(x) => setServer(x.target.value)} required placeholder="chat.example.org" autoCapitalize="none" /></label>
            <label>Benutzername<input value={name} onChange={(x) => setName(x.target.value)} required pattern="[a-z0-9][a-z0-9._\-]{1,31}" title="2–32 Zeichen: a–z, 0–9, . _ -" autoCapitalize="none" autoComplete="off" /></label>
            <label>Einladungscode<input value={invite} onChange={(x) => setInvite(x.target.value)} placeholder="nur bei Einladungs-Servern" autoComplete="off" /></label>
          </>
        ) : (
          <>
            <p className="muted small">Dieses Gerät wird als weiteres Gerät deines Kontos registriert. Ein bereits aktives Gerät nimmt es danach in deine Chats auf (Verlauf nur ab dann).</p>
            <label>Backup-Datei<input type="file" ref={file} /></label>
            <label>Passphrase des Backups<input type="password" value={bpass} onChange={(x) => setBpass(x.target.value)} /></label>
          </>
        )}
        <label>{restore ? 'Neue Passphrase für dieses Gerät' : 'Passphrase (schützt deine Schlüssel lokal)'}
          <input type="password" value={pass} onChange={(x) => setPass(x.target.value)} autoComplete="new-password" required />
        </label>
        <label>Passphrase wiederholen<input type="password" value={pass2} onChange={(x) => setPass2(x.target.value)} autoComplete="new-password" required /></label>
        <label>Mindestlänge der Passphrase (auf diesem Gerät)
          <input type="number" min={1} max={128} value={minLen} onChange={(x) => { const n = Math.min(128, Math.max(1, Number(x.target.value) || 1)); setMinLen(n); setMinPassLength(n); }} />
          {minLen < 8 && <span className="warn">Sehr kurze Passphrasen sind leicht zu erraten. Wer Zugriff auf die verschlüsselten Daten bekommt, kann sie durchprobieren.</span>}
        </label>
        <p className="warn">Es gibt kein „Passwort vergessen“. Ohne Passphrase und ohne Backup ist dein Konto verloren.</p>
        {err && <p className="error" role="alert">{err}</p>}
        <button className="primary" disabled={busy}>{busy ? 'Bitte warten …' : restore ? 'Gerät anmelden' : 'Konto erstellen'}</button>
        <button type="button" className="link" onClick={() => setRestore(!restore)}>
          {restore ? 'Neues Konto erstellen' : 'Mit Backup-Datei auf diesem Gerät anmelden'}
        </button>
      </form>
    </div>
  );
}

export function Unlock({ address }: { address: string }) {
  const e = useEngine();
  const [pass, setPass] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const [remember, setRemember] = useState<RememberMode>(rememberMode());
  async function submit(ev: React.FormEvent) {
    ev.preventDefault();
    setBusy(true);
    setErr('');
    try {
      await e.unlock(pass);
      await saveSession(pass, remember);
    } catch (x) {
      setErr((x as Error).message || 'Entsperren fehlgeschlagen');
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="center">
      <form className="card auth" onSubmit={submit}>
        <h1>Entsperren</h1>
        <p className="muted">{address}</p>
        <label>Passphrase<input type="password" value={pass} onChange={(x) => setPass(x.target.value)} autoFocus autoComplete="current-password" /></label>
        <label>Angemeldet bleiben nach Neuladen
          <select value={remember} onChange={(x) => { const m = x.target.value as RememberMode; setRemember(m); setRememberMode(m); }}>
            {(Object.keys(REMEMBER_LABEL) as RememberMode[]).filter((m) => persistentAvailable() || m === 'off' || m === 'tab').map((m) => <option key={m} value={m}>{REMEMBER_LABEL[m]}</option>)}
          </select>
          {remember !== 'off' && <span className="muted small">Die Passphrase wird dafür auf diesem Gerät vorgehalten. Nur auf eigenen Geräten verwenden; „Sperren“ löscht sie sofort.</span>}
        </label>
        {err && <p className="error" role="alert">{err}</p>}
        <button className="primary" disabled={busy || !pass}>{busy ? 'Entsperre …' : 'Entsperren'}</button>
      </form>
    </div>
  );
}
