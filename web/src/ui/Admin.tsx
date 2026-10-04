import { useEffect, useState } from 'react';
import { formatBytes } from '../lib/util';
import { useEngine } from './hooks';
import type { AdminUser } from '../lib/engine';
import { Dialog } from './Dialog';

type Tab = 'stats' | 'settings' | 'users';

const FIELDS: { key: string; label: string; kind: 'text' | 'number' | 'bool' | 'list' | 'choice'; choices?: string[]; hint?: string }[] = [
  { key: 'registration', label: 'Registrierung', kind: 'choice', choices: ['invite', 'open', 'closed'], hint: 'invite = nur mit Einladung, open = offen (mit Proof-of-Work), closed = geschlossen' },
  { key: 'user_invites', label: 'Nutzer dürfen Einladungen erzeugen', kind: 'bool' },
  { key: 'registration_pow', label: 'Proof-of-Work-Bits bei offener Registrierung (0–28)', kind: 'number' },
  { key: 'federation', label: 'Föderation', kind: 'choice', choices: ['open', 'allowlist', 'closed'] },
  { key: 'federation_allow', label: 'Erlaubte Server (bei allowlist), eine Domain pro Zeile', kind: 'list' },
  { key: 'federation_block', label: 'Gesperrte Server, eine Domain pro Zeile', kind: 'list' },
  { key: 'max_file_size', label: 'Max. Dateigröße (Bytes)', kind: 'number' },
  { key: 'user_quota', label: 'Speicher je Nutzer (Bytes)', kind: 'number' },
  { key: 'max_message_attachments', label: 'Dateien je Nachricht', kind: 'number' },
  { key: 'max_message_total_size', label: 'Gesamtgröße je Nachricht (Bytes)', kind: 'number' },
  { key: 'max_message_text', label: 'Max. Textlänge', kind: 'number' },
  { key: 'max_envelope_size', label: 'Max. Postfach-Blob (Bytes)', kind: 'number' },
  { key: 'blob_retention_days', label: 'Dateien aufbewahren (Tage)', kind: 'number' },
  { key: 'message_retention_days', label: 'Nachrichten-Warteschlange aufbewahren (Tage)', kind: 'number' },
  { key: 'rate_per_minute', label: 'Anfragen pro Minute und IP', kind: 'number' },
  { key: 'max_devices', label: 'Geräte je Konto', kind: 'number' },
  { key: 'max_mailboxes', label: 'Postfächer je Konto', kind: 'number' },
  { key: 'channels', label: 'Öffentliche Kanäle erlauben', kind: 'bool' },
  { key: 'max_channels', label: 'Kanäle je Nutzer', kind: 'number' },
  { key: 'max_channel_members', label: 'Mitglieder je Kanal', kind: 'number' },
  { key: 'max_post_size', label: 'Max. Beitragsgröße (Bytes)', kind: 'number' },
  { key: 'channel_retention_days', label: 'Kanal-Beiträge aufbewahren (Tage)', kind: 'number' },
  { key: 'max_hooks', label: 'Webhooks je Kanal', kind: 'number' },
];

const STAT_LABELS: [string, string][] = [
  ['users', 'Nutzer'], ['admins', 'Administratoren'], ['new_users_24h', 'Neue Nutzer (24 h)'], ['devices', 'Geräte'], ['mailboxes', 'Postfächer'],
  ['queued_messages', 'Wartende Nachrichten'], ['queued_bytes', 'Größe der Warteschlange'], ['blobs', 'Dateien'], ['blob_bytes', 'Dateispeicher'],
  ['channels', 'Kanäle'], ['public_channels', 'davon öffentlich'], ['channel_members', 'Kanal-Mitglieder'], ['channel_posts', 'Kanal-Beiträge'],
  ['channel_posts_24h', 'Beiträge (24 h)'], ['webhooks', 'Webhooks'], ['open_invites', 'Offene Einladungen'], ['db_bytes', 'Datenbank'],
];

function dur(s: number): string {
  const d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60);
  return `${d ? d + ' d ' : ''}${h} h ${m} min`;
}

export function AdminDialog({ onClose }: { onClose: () => void }) {
  const e = useEngine();
  const [tab, setTab] = useState<Tab>('stats');
  return (
    <Dialog title="Server-Administration" onClose={onClose} wide>
      <p className="muted small">Du bist Administrator dieses Servers ({e.state!.me.domain}). Änderungen gelten sofort und bleiben über Neustarts erhalten.</p>
      <div className="tabs" role="tablist">
        {([['stats', 'Statistik'], ['settings', 'Einstellungen'], ['users', 'Nutzer']] as [Tab, string][]).map(([t, l]) => (
          <button key={t} role="tab" aria-selected={tab === t} className={tab === t ? 'on' : ''} onClick={() => setTab(t)}>{l}</button>
        ))}
      </div>
      {tab === 'stats' && <Stats />}
      {tab === 'settings' && <ServerSettings />}
      {tab === 'users' && <Users />}
    </Dialog>
  );
}

function Stats() {
  const e = useEngine();
  const [d, setD] = useState<Awaited<ReturnType<typeof e.adminStats>> | null>(null);
  const [err, setErr] = useState('');
  const load = () => e.adminStats().then(setD).catch((x) => setErr((x as Error).message));
  useEffect(() => { void load(); const t = setInterval(load, 10_000); return () => clearInterval(t); }, []); // eslint-disable-line react-hooks/exhaustive-deps
  if (err) return <p className="error">{err}</p>;
  if (!d) return <p className="muted">Lade …</p>;
  const isBytes = (k: string) => k.endsWith('_bytes');
  return (
    <>
      <div className="stat-grid">
        {STAT_LABELS.map(([k, l]) => (
          <div key={k} className="stat"><div className="stat-v">{isBytes(k) ? formatBytes(d.stats[k] ?? 0) : (d.stats[k] ?? 0)}</div><div className="muted small">{l}</div></div>
        ))}
        <div className="stat"><div className="stat-v">{dur(d.uptime_seconds)}</div><div className="muted small">Laufzeit</div></div>
        <div className="stat"><div className="stat-v">{d.requests_total}</div><div className="muted small">Anfragen seit Start</div></div>
        <div className="stat"><div className="stat-v">{d.ws_connections}</div><div className="muted small">Live-Verbindungen</div></div>
        <div className="stat"><div className="stat-v">{formatBytes(d.memory_bytes)}</div><div className="muted small">Arbeitsspeicher</div></div>
      </div>
      <p className="muted small">Aktualisiert sich alle 10 Sekunden. Es werden keine Inhalte und keine IP-Adressen erfasst, nur Zähler.</p>
    </>
  );
}

function ServerSettings() {
  const e = useEngine();
  const [st, setSt] = useState<Record<string, unknown> | null>(null);
  const [orig, setOrig] = useState('');
  const [msg, setMsg] = useState('');
  const [err, setErr] = useState('');
  useEffect(() => { e.adminSettings().then((s) => { setSt(s); setOrig(JSON.stringify(s)); }).catch((x) => setErr((x as Error).message)); }, [e]);
  if (!st) return <p className={err ? 'error' : 'muted'}>{err || 'Lade …'}</p>;
  const dirty = JSON.stringify(st) !== orig;
  const set = (k: string, v: unknown) => { setSt({ ...st, [k]: v }); setMsg(''); };
  async function save() {
    setErr('');
    try {
      const n = await e.saveAdminSettings(st!);
      setSt(n);
      setOrig(JSON.stringify(n));
      setMsg('✔ Gespeichert und aktiv');
    } catch (x) { setErr((x as Error).message); }
  }
  return (
    <>
      {FIELDS.map((f) => (
        <label key={f.key} className={f.kind === 'bool' ? 'check' : ''}>
          {f.kind === 'bool' ? (
            <><input type="checkbox" checked={!!st[f.key]} onChange={(x) => set(f.key, x.target.checked)} /> {f.label}</>
          ) : (
            <>
              {f.label}
              {f.kind === 'choice' && <select value={String(st[f.key])} onChange={(x) => set(f.key, x.target.value)}>{f.choices!.map((c) => <option key={c}>{c}</option>)}</select>}
              {f.kind === 'number' && <input type="number" min={0} value={Number(st[f.key] ?? 0)} onChange={(x) => set(f.key, Number(x.target.value))} />}
              {f.kind === 'list' && <textarea rows={3} value={((st[f.key] as string[]) ?? []).join('\n')} onChange={(x) => set(f.key, x.target.value.split('\n'))} />}
              {f.hint && <span className="muted small">{f.hint}</span>}
            </>
          )}
        </label>
      ))}
      <div className="row savebar">
        <button className="primary" disabled={!dirty} onClick={save}>Speichern</button>
        <button disabled={!dirty} onClick={() => { setSt(JSON.parse(orig)); setMsg(''); }}>Verwerfen</button>
        {dirty && <span className="warn">Ungespeicherte Änderungen</span>}
        {msg && !dirty && <span className="ok">{msg}</span>}
      </div>
      {err && <p className="error">{err}</p>}
      <p className="muted small">Domain und Listen-Adresse lassen sich nur über die Umgebungsvariablen ändern (Neustart nötig).</p>
    </>
  );
}

const UNITS: [string, number][] = [['Minuten', 1], ['Stunden', 60], ['Tage', 1440]];

function Users() {
  const e = useEngine();
  const [us, setUs] = useState<AdminUser[]>([]);
  const [q, setQ] = useState('');
  const [err, setErr] = useState('');
  const [sel, setSel] = useState<AdminUser | null>(null);
  const load = (query = q) => e.adminUsers(query).then((r) => setUs(r.users)).catch((x) => setErr((x as Error).message));
  useEffect(() => { void load(); }, []); // eslint-disable-line react-hooks/exhaustive-deps
  const now = Date.now() / 1000;
  const banLabel = (u: AdminUser) => (u.banned_until === -1 ? 'Dauerhaft gesperrt' : u.banned_until > now ? `Gesperrt bis ${new Date(u.banned_until * 1000).toLocaleString()}` : '');
  const rateLabel = (u: AdminUser) => (u.rate_limit > 0 && (u.rate_until === 0 || u.rate_until > now) ? `Limit ${u.rate_limit}/Min${u.rate_until ? ` bis ${new Date(u.rate_until * 1000).toLocaleString()}` : ''}` : '');
  return (
    <>
      <form className="row" onSubmit={(ev) => { ev.preventDefault(); void load(); }}>
        <input aria-label="Nutzer suchen" placeholder="Nutzer suchen …" value={q} onChange={(ev) => setQ(ev.target.value)} />
        <button type="submit">Suchen</button>
      </form>
      <ul className="members">
        {us.map((u) => (
          <li key={u.name}>
            <div className="grow">
              <strong>{u.name}</strong> {u.admin && <span className="ok">Admin</span>}
              {banLabel(u) && <span className="error"> {banLabel(u)}{u.ban_reason ? ` (${u.ban_reason})` : ''}</span>}
              {rateLabel(u) && <span className="muted"> · {rateLabel(u)}</span>}
              <div className="muted small">seit {new Date(u.created_at * 1000).toLocaleDateString()} · {u.devices} Geräte · {formatBytes(u.blob_bytes)} Dateien · {u.channels} Kanäle</div>
            </div>
            <button onClick={async () => { setErr(''); try { await e.setAdmin(u.name, !u.admin); await load(); } catch (x) { setErr((x as Error).message); } }}>{u.admin ? 'Admin entziehen' : 'Zum Admin machen'}</button>
            {!u.admin && <button onClick={() => setSel(u)}>Sperren / Limit …</button>}
          </li>
        ))}
        {us.length === 0 && <li className="muted">Keine Nutzer gefunden.</li>}
      </ul>
      {err && <p className="error">{err}</p>}
      {sel && <RestrictDialog user={sel} onClose={() => setSel(null)} onDone={() => { setSel(null); void load(); }} />}
    </>
  );
}

function RestrictDialog({ user, onClose, onDone }: { user: AdminUser; onClose: () => void; onDone: () => void }) {
  const e = useEngine();
  const now = Date.now() / 1000;
  const [ban, setBan] = useState<'' | 'perm' | 'temp'>(user.banned_until === -1 ? 'perm' : user.banned_until > now ? 'temp' : '');
  const [dur, setDur] = useState(1);
  const [unit, setUnit] = useState(60);
  const [reason, setReason] = useState(user.ban_reason);
  const [rate, setRate] = useState(user.rate_limit > 0 ? String(user.rate_limit) : '');
  const [rdur, setRdur] = useState(0);
  const [runit, setRunit] = useState(60);
  const [err, setErr] = useState('');
  const save = async () => {
    setErr('');
    try {
      await e.restrictUser(user.name, { ban, ban_minutes: ban === 'temp' ? dur * unit : 0, reason, rate_limit: Number(rate) || 0, rate_minutes: rdur * runit });
      onDone();
    } catch (x) { setErr((x as Error).message); }
  };
  return (
    <Dialog title={`Sperre für ${user.name}`} onClose={onClose}>
      <div>
        <label>Sperre
          <select aria-label="Sperre-Art" value={ban} onChange={(ev) => setBan(ev.target.value as '' | 'perm' | 'temp')}>
            <option value="">Keine</option><option value="temp">Zeitweise</option><option value="perm">Dauerhaft (Bann)</option>
          </select>
        </label>
        {ban === 'temp' && (
          <div className="row">
            <input type="number" min={1} aria-label="Dauer" value={dur} onChange={(ev) => setDur(Math.max(1, Number(ev.target.value)))} />
            <select aria-label="Einheit" value={unit} onChange={(ev) => setUnit(Number(ev.target.value))}>{UNITS.map(([n, v]) => <option key={n} value={v}>{n}</option>)}</select>
          </div>
        )}
        {ban !== '' && <label>Grund (optional, sieht der Nutzer)<input value={reason} maxLength={300} onChange={(ev) => setReason(ev.target.value)} /></label>}
        <label>Nachrichten-/Anfragelimit pro Minute (leer = Standard)<input type="number" min={0} value={rate} onChange={(ev) => setRate(ev.target.value)} /></label>
        {Number(rate) > 0 && (
          <div className="row">
            <input type="number" min={0} aria-label="Limit-Dauer" value={rdur} onChange={(ev) => setRdur(Math.max(0, Number(ev.target.value)))} />
            <select aria-label="Limit-Einheit" value={runit} onChange={(ev) => setRunit(Number(ev.target.value))}>{UNITS.map(([n, v]) => <option key={n} value={v}>{n}</option>)}</select>
            <span className="muted small">0 = unbefristet</span>
          </div>
        )}
        <p className="muted small">Eine Sperre beendet sofort jeden Zugriff des Kontos auf diesen Server (Postfächer, Sync, Dateien, Kanäle). Nachrichten werden Ende-zu-Ende zugestellt und sind dem Server nicht zuordenbar; das Limit drosselt daher alle authentifizierten Anfragen des Kontos.</p>
        {err && <p className="error">{err}</p>}
        <div className="row"><button onClick={onClose}>Abbrechen</button><button className="primary" onClick={save}>Speichern</button></div>
      </div>
    </Dialog>
  );
}
