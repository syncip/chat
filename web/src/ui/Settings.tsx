import { useEffect, useState } from 'react';
import type { DeviceInfo } from '../lib/types';
import { copyText, formatBytes } from '../lib/util';
import { useEngine } from './hooks';
import { QrCode } from './QrCode';
import { enrollPasskey, hasPasskey, passkeySupported, removePasskey } from '../lib/passkey';
import { minPassLength, setMinPassLength, rememberMode, setRememberMode, REMEMBER_LABEL, idleLockMinutes, setIdleLockMinutes, type RememberMode } from '../lib/prefs';
import { soundEnabled, setSoundEnabled, playNotify } from '../lib/sound';
import { clearSession, persistentAvailable } from '../lib/session';
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
  const [devices, setDevices] = useState<DeviceInfo[]>([]);
  const [minLen, setMinLen] = useState(minPassLength());
  const [remember, setRemember] = useState<RememberMode>(rememberMode());
  const [idle, setIdle] = useState(idleLockMinutes());
  const [sound, setSound] = useState(soundEnabled());
  const loadDevices = () => e.listDevices().then(setDevices).catch(() => undefined);
  const link = e.contactLink();

  useEffect(() => { e.quota().then(setQuota).catch(() => undefined); void loadDevices(); }, [e]); // eslint-disable-line react-hooks/exhaustive-deps
  const run = async (f: () => Promise<unknown> | unknown, ok = '') => {
    setMsg('');
    try { await f(); if (ok) setMsg(ok); } catch (x) { setMsg((x as Error).message); }
  };
  const lim = e.info?.limits;

  function download() {
    if (backupPass.length < minPassLength()) return setMsg(`Die Backup-Passphrase braucht mindestens ${minPassLength()} Zeichen.`);
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
        <h3>Anmeldung &amp; Sperre (nur hier)</h3>
        <label>Angemeldet bleiben nach Neuladen
          <select value={remember} onChange={(x) => { const m = x.target.value as RememberMode; setRemember(m); setRememberMode(m); if (m === 'off') void clearSession(); else setMsg('Gilt ab der nächsten Anmeldung.'); }}>
            {(Object.keys(REMEMBER_LABEL) as RememberMode[]).filter((m) => persistentAvailable() || m === 'off' || m === 'tab').map((m) => <option key={m} value={m}>{REMEMBER_LABEL[m]}</option>)}
          </select>
          <span className="muted small">Dafür wird die Passphrase zeitlich begrenzt auf diesem Gerät vorgehalten (verschlüsselt mit einem nicht exportierbaren Browser-Schlüssel). Nur auf eigenen Geräten nutzen.{!persistentAvailable() ? ' Ohne TLS (https) ist nur „Tab“ möglich.' : ''}</span>
        </label>
        <PasskeySection setMsg={setMsg} />
        <label>Automatisch sperren nach Inaktivität (Minuten, 0 = nie)
          <input type="number" min={0} value={idle} onChange={(x) => { const n = Math.max(0, Number(x.target.value) || 0); setIdle(n); setIdleLockMinutes(n); }} />
        </label>
        <label className="check">
          <input type="checkbox" checked={sound} onChange={(x) => { setSound(x.target.checked); setSoundEnabled(x.target.checked); if (x.target.checked) playNotify(); }} /> Benachrichtigungston bei neuen Nachrichten
        </label>
        <label>Mindestlänge für Passphrasen (Backup, neue Konten)
          <input type="number" min={1} max={128} value={minLen} onChange={(x) => { const n = Math.min(128, Math.max(1, Number(x.target.value) || 1)); setMinLen(n); setMinPassLength(n); }} />
          {minLen < 8 && <span className="warn">Sehr kurze Passphrasen sind leicht zu erraten.</span>}
        </label>
        <button onClick={() => { void clearSession(); void e.lock(); }}>Jetzt sperren</button>
      </section>
      <section>
        <h3>Dein Kontaktlink</h3>
        {s.intro ? (
          <>
            <p className="muted small">Wer diesen Link hat, kann dir eine Chat-Anfrage schicken. Du entscheidest, ob du sie annimmst.</p>
            <input readOnly value={link} onFocus={(x) => x.target.select()} />
            <div className="row">
              <button onClick={() => run(() => copyText(link), 'Link kopiert.')}>Kopieren</button>
              <button onClick={() => run(() => e.setIntroEnabled(false), 'Kontaktlink deaktiviert (nur noch bestehende Kontakte).')}>Deaktivieren</button>
              <button onClick={() => run(async () => { await e.setIntroEnabled(false); await e.setIntroEnabled(true); }, 'Neuer Link erzeugt, der alte ist ungültig (ein Chat-Code muss danach neu gespeichert werden).')}>Neu erzeugen</button>
            </div>
            <ChatCodeSection link={link} />
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
        <h3>Bestätigungen &amp; Einmal-Nachrichten</h3>
        <label className="check"><input type="checkbox" checked={s.sendDelivered} onChange={(x) => e.setReceiptSettings({ sendDelivered: x.target.checked })} />
          „Empfangen“ an Gesprächspartner senden (private Chats). Wer das ausschaltet, sieht es von anderen auch nicht.</label>
        <label className="check"><input type="checkbox" checked={s.sendRead} onChange={(x) => e.setReceiptSettings({ sendRead: x.target.checked })} />
          „Gelesen“ senden (private Chats). Wer das ausschaltet, sieht es von anderen auch nicht.</label>
        <label className="check"><input type="checkbox" checked={s.onceDropOwnCopy} onChange={(x) => e.setReceiptSettings({ onceDropOwnCopy: x.target.checked })} />
          Einmal-Nachrichten: eigene Kopie sofort entfernen (z. B. bei Kennwörtern)</label>
        <p className="muted small">Bestätigungen verraten dem Gegenüber Zeitpunkte, deshalb sind sie standardmäßig aus. „Gesendet“ (✓) siehst du immer. Einmal-Nachrichten melden dem Absender beim Anzeigen immer „gelesen“.</p>
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

      <p className="muted small">Chat Web {__APP_VERSION__}{(e.info as { app_version?: string } | undefined)?.app_version ? ` · Server ${(e.info as { app_version?: string }).app_version}` : ''}</p>

      <section>
        <h3>Geräte</h3>
        <p className="muted small">Dein Konto kann auf mehreren Geräten gleichzeitig aktiv sein. Ein neues Gerät meldest du mit der Backup-Datei an; ein aktives Gerät nimmt es dann in deine Chats auf.</p>
        <ul>
          {devices.map((d) => (
            <li key={d.id}>
              <code>{d.id.slice(0, 8)}</code> · seit {new Date(d.created_at * 1000).toLocaleDateString()} {d.current && <strong>(dieses Gerät)</strong>}{' '}
              {!d.current && <button className="link" onClick={() => { if (confirm('Gerät widerrufen? Es verliert Anmeldung und wird aus deinen Chats entfernt.')) void run(async () => { await e.revokeDevice(d.id); await loadDevices(); }, 'Gerät widerrufen.'); }}>widerrufen</button>}
            </li>
          ))}
        </ul>
        <DeviceLinkSection />
      </section>

      <section>
        <h3>Backup &amp; Sitzung</h3>
        <p className="muted small">Die Backup-Datei enthält deinen Konto-Schlüssel und den aktuellen Stand deiner Einstellungen und Kontakte (keinen Nachrichtenverlauf). Speichere sie erneut, wenn sich Kontakte oder Einstellungen geändert haben.</p>
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

function PasskeySection({ setMsg }: { setMsg: (m: string) => void }) {
  const e = useEngine();
  const [has, setHas] = useState(false);
  const [pass, setPass] = useState('');
  const [err, setErr] = useState('');
  const ok = passkeySupported();
  useEffect(() => { void hasPasskey().then(setHas); }, []);
  if (!ok) return <p className="muted small">Passkey-Entsperren: braucht https oder localhost und einen Browser mit WebAuthn.</p>;
  return (
    <div>
      <strong>Entsperren mit Passkey</strong>
      <p className="muted small">Fingerabdruck, Geräte-PIN oder Sicherheitsschlüssel entsperren diesen Browser statt der Passphrase. Die Passphrase bleibt für Backup und neue Geräte nötig. Der Browser muss die WebAuthn-PRF-Erweiterung unterstützen.</p>
      {has ? (
        <button onClick={async () => { await removePasskey(); setHas(false); setMsg('Passkey entfernt.'); }}>Passkey entfernen</button>
      ) : (
        <div className="row">
          <input type="password" aria-label="Passphrase für Passkey" placeholder="Aktuelle Passphrase" value={pass} onChange={(x) => setPass(x.target.value)} />
          <button disabled={!pass} onClick={async () => {
            setErr('');
            try {
              if (!(await e.verifyPassphrase(pass))) throw new Error('Passphrase ist falsch.');
              await enrollPasskey(pass, e.state?.me.address ?? 'Chat');
              setPass(''); setHas(true); setMsg('Passkey eingerichtet.');
            } catch (x) { setErr((x as Error).message); }
          }}>Passkey einrichten</button>
        </div>
      )}
      {err && <p className="error" role="alert">{err}</p>}
    </div>
  );
}

function ChatCodeSection({ link }: { link: string }) {
  const e = useEngine();
  const [code, setCode] = useState('');
  const [saved, setSaved] = useState('');
  const [msg, setMsg] = useState('');
  const [err, setErr] = useState('');
  const [qr, setQr] = useState(false);
  useEffect(() => { e.myChatCode().then((c) => { setSaved(c); setCode(c); }).catch(() => undefined); }, [e]);
  return (
    <div>
      <strong>Mein Chat-Code &amp; QR-Code</strong>
      <p className="muted small">Wähle einen Code (3–40 Zeichen: a–z, 0–9, _ und -). Wer ihn bei „Neuer Chat“ eingibt, landet bei dir. Der Code ist öffentlich auflösbar: wer ihn errät, kann dir eine Chat-Anfrage schicken (du entscheidest, ob du sie annimmst). Wähle ihn deshalb nicht zu einfach, wenn du Anfragen von Fremden vermeiden willst.</p>
      <div className="row">
        <input aria-label="Chat-Code" value={code} placeholder="z. B. martinistcool" onChange={(x) => setCode(x.target.value.toLowerCase())} />
        <button disabled={!code || code === saved} onClick={async () => {
          setErr(''); setMsg('');
          try { const c = await e.setChatCode(code); setSaved(c); setCode(c); setMsg(`Chat-Code „${c}“ gespeichert.`); } catch (x) { setErr((x as Error).message); }
        }}>Code speichern</button>
        {saved && <button onClick={async () => { await e.removeChatCode(); setSaved(''); setCode(''); setMsg('Chat-Code entfernt.'); }}>Entfernen</button>}
      </div>
      {link && <button onClick={() => setQr(!qr)}>{qr ? 'QR-Code ausblenden' : 'Meinen Kontakt-QR-Code anzeigen'}</button>}
      {qr && link && <QrCode text={link} label="QR-Code deines Kontaktlinks" />}
      {msg && <p className="ok" role="status">{msg}</p>}
      {err && <p className="error" role="alert">{err}</p>}
    </div>
  );
}

function DeviceLinkSection() {
  const e = useEngine();
  const [link, setLink] = useState<{ link: string; until: number } | null>(null);
  const [left, setLeft] = useState(0);
  const [err, setErr] = useState('');
  useEffect(() => {
    if (!link) return;
    const t = setInterval(() => {
      const l = Math.max(0, Math.round((link.until - Date.now()) / 1000));
      setLeft(l);
      if (l === 0) setLink(null);
    }, 500);
    return () => clearInterval(t);
  }, [link]);
  return (
    <div>
      <strong>Android-Gerät per QR-Code anmelden</strong>
      <p className="muted small">Öffne in der Android-App „Per QR-Code anmelden“ und scanne den Code. Er enthält einen Einmalschlüssel, gilt 5 Minuten und nur einmal. Zeige ihn niemandem und mache kein Foto davon: Wer ihn scannt, kann dein Konto auf einem Gerät hinzufügen (du siehst es danach in der Geräteliste und kannst es widerrufen).</p>
      {link ? (
        <>
          <QrCode text={link.link} label="QR-Code zum Anmelden eines Geräts" />
          <p className="muted small" role="status">Gültig noch {Math.floor(left / 60)}:{String(left % 60).padStart(2, '0')} Minuten</p>
          <button onClick={() => setLink(null)}>Code ausblenden</button>
        </>
      ) : (
        <button onClick={async () => {
          setErr('');
          try { const r = await e.createDeviceLink(); setLink({ link: r.link, until: Date.now() + r.expiresIn * 1000 }); setLeft(r.expiresIn); } catch (x) { setErr((x as Error).message); }
        }}>QR-Code anzeigen</button>
      )}
      {err && <p className="error" role="alert">{err}</p>}
    </div>
  );
}
