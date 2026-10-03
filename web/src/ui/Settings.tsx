import { useEffect, useState } from 'react';
import { copyText, formatBytes } from '../lib/util';
import { useEngine } from './hooks';
import { Dialog } from './Dialog';

export function Settings({ onClose }: { onClose: () => void }) {
  const e = useEngine();
  const s = e.state!;
  const [quota, setQuota] = useState<{ used: number; quota: number } | null>(null);
  const [msg, setMsg] = useState('');
  const [invite, setInvite] = useState('');
  const [newBlockUser, setNewBlockUser] = useState('');
  const [newBlockServer, setNewBlockServer] = useState('');
  const [newAllow, setNewAllow] = useState('');
  const [backupPass, setBackupPass] = useState('');
  const link = e.contactLink();

  useEffect(() => { e.quota().then(setQuota).catch(() => undefined); }, [e]);
  const run = async (f: () => Promise<unknown> | unknown, ok = '') => {
    setMsg('');
    try { await f(); if (ok) setMsg(ok); } catch (x) { setMsg((x as Error).message); }
  };
  const lim = e.info?.limits;

  function download() {
    if (backupPass.length < 10) return setMsg('Die Backup-Passphrase braucht mindestens 10 Zeichen.');
    const data = e.exportBackup(backupPass);
    const url = URL.createObjectURL(new Blob([data as BlobPart], { type: 'application/octet-stream' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = `chat-backup-${s.me.name}.bak`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 10_000);
    setMsg('Backup gespeichert. Bewahre Datei und Passphrase getrennt auf.');
  }

  return (
    <Dialog title="Einstellungen" onClose={onClose} wide>
      <section>
        <h3>Dein Kontaktlink</h3>
        {s.intro ? (
          <>
            <p className="muted small">Wer diesen Link hat, kann dir eine Chat-Anfrage schicken. Du entscheidest, ob du sie annimmst.</p>
            <input readOnly value={link} onFocus={(x) => x.target.select()} />
            <div className="row">
              <button onClick={() => run(() => copyText(link), 'Link kopiert.')}>Kopieren</button>
              <button onClick={() => run(() => e.setIntroEnabled(false), 'Kontaktlink deaktiviert (nur noch bestehende Kontakte).')}>Deaktivieren</button>
              <button onClick={() => run(async () => { await e.setIntroEnabled(false); await e.setIntroEnabled(true); }, 'Neuer Link erzeugt, der alte ist ungültig.')}>Neu erzeugen</button>
            </div>
          </>
        ) : (
          <>
            <p className="muted small">Der Kontaktlink ist aus: Niemand Neues kann dich erreichen.</p>
            <button onClick={() => run(() => e.setIntroEnabled(true))}>Aktivieren</button>
          </>
        )}
      </section>

      <section>
        <h3>Blockieren &amp; Allowlist</h3>
        <label>Modus
          <select value={s.filterMode} onChange={(x) => run(() => e.setFilterMode(x.target.value as 'off' | 'block' | 'allow'))}>
            <option value="off">Offen (nur blockierte werden verworfen)</option>
            <option value="allow">Nur erlaubte Nutzer/Server und verifizierte Kontakte</option>
          </select>
        </label>
        <ListEditor title="Blockierte Nutzer" items={s.blockedUsers} value={newBlockUser} setValue={setNewBlockUser}
          placeholder="name@server" onAdd={(v) => run(async () => { await e.blockUser(v.trim().toLowerCase()); setNewBlockUser(''); })} onRemove={(v) => e.unblockUser(v)} />
        <ListEditor title="Blockierte Server" items={s.blockedServers} value={newBlockServer} setValue={setNewBlockServer}
          placeholder="server.example" onAdd={(v) => run(async () => { await e.blockServer(v); setNewBlockServer(''); })} onRemove={(v) => run(() => e.unblockServer(v))} />
        <ListEditor title="Erlaubt (Nutzer oder Server)" items={[...s.allowUsers, ...s.allowServers]} value={newAllow} setValue={setNewAllow}
          placeholder="name@server oder server.example"
          onAdd={(v) => run(async () => { await e.setAllow(v.includes('@') ? 'user' : 'server', v, true); setNewAllow(''); })}
          onRemove={(v) => run(() => e.setAllow(v.includes('@') ? 'user' : 'server', v, false))} />
        <label className="check"><input type="checkbox" checked={s.serverSideFilter} onChange={(x) => run(() => e.setServerSideFilter(x.target.checked))} />
          Server-Liste beim Home-Server hinterlegen (spart Bandbreite, verrät dem Server aber gehashte Domains)</label>
        <p className="muted small">Blockierte Absender erfahren nichts davon. Nachrichten werden stillschweigend verworfen.</p>
      </section>

      <section>
        <h3>Netzwerk</h3>
        <label className="check"><input type="checkbox" checked={s.directSend} onChange={(x) => run(() => e.setDirectSend(x.target.checked))} />
          Direkt an Empfänger-Server senden (statt über deinen Home-Server; dann sieht der Ziel-Server deine IP – ein VPN/Tor wird empfohlen)</label>
      </section>

      <section>
        <h3>Server &amp; Speicher</h3>
        <p className="small">Home-Server: <strong>{s.me.domain}</strong> · Föderation: {e.info?.federation ?? '?'} · Registrierung: {e.info?.registration ?? '?'}</p>
        {quota && (
          <>
            <progress value={quota.used} max={quota.quota} />
            <div className="small muted">{formatBytes(quota.used)} von {formatBytes(quota.quota)} belegt</div>
          </>
        )}
        {lim && <p className="small muted">Limits: Datei {formatBytes(lim.max_file_size)}, {lim.max_message_attachments} Dateien / {formatBytes(lim.max_message_total_size)} pro Nachricht, Dateien {lim.blob_retention_days} Tage gespeichert.</p>}
        {e.info?.client_hash && <p className="small muted mono" title="Hash des ausgelieferten Web-Clients (zur Verifikation durch native Clients/Add-ons)">Client-Hash: {e.info.client_hash.slice(0, 32)}…</p>}
        <div className="row">
          <button onClick={() => run(async () => setInvite(await e.createInvite()))}>Einladungscode erzeugen</button>
          {invite && <code>{invite}</code>}
        </div>
      </section>

      <section>
        <h3>Backup &amp; Sitzung</h3>
        <label>Backup-Passphrase<input type="password" value={backupPass} onChange={(x) => setBackupPass(x.target.value)} autoComplete="new-password" /></label>
        <div className="row">
          <button onClick={download}>Verschlüsseltes Backup laden</button>
          <button onClick={() => e.lock().then(onClose)}>Sperren</button>
          <button className="danger" onClick={() => { if (confirm('Konto lokal löschen? Ohne Backup ist es unwiederbringlich verloren.')) void e.deleteAccount(); }}>Konto lokal löschen</button>
        </div>
      </section>
      {msg && <p className="small" role="status">{msg}</p>}
    </Dialog>
  );
}

function ListEditor({ title, items, value, setValue, onAdd, onRemove, placeholder }: {
  title: string; items: string[]; value: string; setValue: (v: string) => void;
  onAdd: (v: string) => void; onRemove: (v: string) => void; placeholder: string;
}) {
  return (
    <div className="listeditor">
      <div className="muted small">{title}</div>
      <ul>{items.map((i) => <li key={i}>{i} <button className="link" onClick={() => onRemove(i)}>entfernen</button></li>)}</ul>
      <div className="row">
        <input value={value} onChange={(x) => setValue(x.target.value)} placeholder={placeholder} autoCapitalize="none" />
        <button disabled={!value.trim()} onClick={() => onAdd(value)}>Hinzufügen</button>
      </div>
    </div>
  );
}
