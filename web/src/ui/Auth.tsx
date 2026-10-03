import { useRef, useState } from 'react';
import { useEngine } from './hooks';

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

  async function submit(ev: React.FormEvent) {
    ev.preventDefault();
    setErr('');
    if (pass.length < 10) return setErr('Die Passphrase braucht mindestens 10 Zeichen.');
    if (pass !== pass2) return setErr('Die Passphrasen stimmen nicht überein.');
    setBusy(true);
    try {
      if (restore) {
        const f = file.current?.files?.[0];
        if (!f) throw new Error('Bitte Backup-Datei wählen.');
        await e.restoreBackup(new Uint8Array(await f.arrayBuffer()), bpass, pass);
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
            <label>Backup-Datei<input type="file" ref={file} /></label>
            <label>Passphrase des Backups<input type="password" value={bpass} onChange={(x) => setBpass(x.target.value)} /></label>
          </>
        )}
        <label>{restore ? 'Neue Passphrase für dieses Gerät' : 'Passphrase (schützt deine Schlüssel lokal)'}
          <input type="password" value={pass} onChange={(x) => setPass(x.target.value)} autoComplete="new-password" required />
        </label>
        <label>Passphrase wiederholen<input type="password" value={pass2} onChange={(x) => setPass2(x.target.value)} autoComplete="new-password" required /></label>
        <p className="warn">Es gibt kein „Passwort vergessen“. Ohne Passphrase und ohne Backup ist dein Konto verloren.</p>
        {err && <p className="error" role="alert">{err}</p>}
        <button className="primary" disabled={busy}>{busy ? 'Bitte warten …' : restore ? 'Backup wiederherstellen' : 'Konto erstellen'}</button>
        <button type="button" className="link" onClick={() => setRestore(!restore)}>
          {restore ? 'Neues Konto erstellen' : 'Konto aus Backup wiederherstellen'}
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
  async function submit(ev: React.FormEvent) {
    ev.preventDefault();
    setBusy(true);
    setErr('');
    try {
      await e.unlock(pass);
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
        {err && <p className="error" role="alert">{err}</p>}
        <button className="primary" disabled={busy || !pass}>{busy ? 'Entsperre …' : 'Entsperren'}</button>
      </form>
    </div>
  );
}
