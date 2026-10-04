import { useState } from 'react';
import { useEngine } from './hooks';
import { minPassLength } from '../lib/prefs';

/** Pflicht nach der Registrierung: Backup-Datei speichern (sie ist die Anmeldung auf weiteren Geräten und die Wiederherstellung). */
export function BackupGate() {
  const e = useEngine();
  const [p1, setP1] = useState('');
  const [p2, setP2] = useState('');
  const [err, setErr] = useState('');
  const [saved, setSaved] = useState(false);

  function save() {
    setErr('');
    if (p1.length < minPassLength()) return setErr(`Die Backup-Passphrase braucht mindestens ${minPassLength()} Zeichen.`);
    if (p1 !== p2) return setErr('Die Passphrasen stimmen nicht überein.');
    const data = e.exportBackup(p1);
    const url = URL.createObjectURL(new Blob([data as BlobPart], { type: 'application/octet-stream' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = `chat-backup-${e.state!.me.name}.bak`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 10_000);
    setSaved(true);
  }

  return (
    <div className="overlay">
      <div className="dialog card" role="dialog" aria-modal="true" aria-label="Backup speichern">
        <h2>Backup-Datei speichern</h2>
        <p>
          Die Backup-Datei enthält deinen Konto-Schlüssel. Du brauchst sie, um dich auf <strong>weiteren Geräten anzumelden</strong> oder
          dein Konto nach einem Geräteverlust wiederherzustellen. Ohne sie ist ein verlorenes Gerät auch ein verlorenes Konto.
        </p>
        <label>Backup-Passphrase (mindestens {minPassLength()} Zeichen)<input type="password" value={p1} onChange={(x) => setP1(x.target.value)} autoComplete="new-password" /></label>
        <label>Wiederholen<input type="password" value={p2} onChange={(x) => setP2(x.target.value)} autoComplete="new-password" /></label>
        <p className="warn">Bewahre Datei und Passphrase getrennt und sicher auf. Wer beides hat, kann sich als du anmelden.</p>
        {err && <p className="error" role="alert">{err}</p>}
        {!saved ? (
          <button className="primary" onClick={save}>Backup-Datei speichern</button>
        ) : (
          <>
            <p className="ok">Datei wurde heruntergeladen. Bitte prüfe, dass sie gespeichert ist.</p>
            <button className="primary" onClick={() => e.markBackupDone()}>Ich habe das Backup gespeichert</button>
          </>
        )}
      </div>
    </div>
  );
}
