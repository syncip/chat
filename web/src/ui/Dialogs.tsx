import { useState } from 'react';
import { decodeCard } from '../lib/engine';
import { useEngine } from './hooks';
import { Dialog } from './Dialog';

/** Ein Dialog für alles Neue: Link, Code oder Einladung einfügen (die App erkennt, ob es ein Kontakt, ein Chat-Code, ein Kanal oder ein öffentlicher Kanal ist) oder etwas erstellen. */
export function AddDialog({ onClose, onStarted, onGroup, onChannel, onJoinLink }: {
  onClose: () => void; onStarted: (id: string) => void; onGroup: () => void; onChannel: () => void; onJoinLink: (hash: string) => void;
}) {
  const e = useEngine();
  const [link, setLink] = useState('');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  async function go() {
    setBusy(true);
    setErr('');
    try {
      const t = link.trim();
      const join = /#\/join\/[A-Za-z0-9_-]+/.exec(t);
      const pubv = /#\/c\/[A-Za-z0-9_-]+/.exec(t);
      if (join) { onJoinLink(join[0]); return; }
      if (pubv) { onClose(); location.hash = pubv[0]; return; }
      const isCode = t.length <= 80 && /^[A-Za-z0-9][A-Za-z0-9_-]{2,39}(@[A-Za-z0-9.:-]+)?$/.test(t);
      onStarted(await e.startChat(isCode ? await e.resolveChatCode(t) : decodeCard(link)));
    } catch (x) {
      setErr((x as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <Dialog title="Hinzufügen" onClose={onClose}>
      <p className="muted small">Füge einen Link oder Code ein: Kontaktlink oder Chat-Code (z. B. <code>martinistcool</code>, bei anderen Servern <code>code@server</code>) startet einen Chat, ein Kanal-Link tritt einem Kanal bei. Die App erkennt selbst, worum es geht.</p>
      <textarea aria-label="Link oder Chat-Code" value={link} onChange={(x) => setLink(x.target.value)} rows={3} placeholder="Link, Einladung oder Chat-Code" />
      {err && <p className="error">{err}</p>}
      <button className="primary" disabled={busy || !link.trim()} onClick={() => void go()}>Weiter</button>
      <h3>Oder neu erstellen</h3>
      <div className="row wrap">
        <button type="button" disabled={busy} onClick={onGroup}>👥 Gruppe erstellen</button>
        <button type="button" disabled={busy} onClick={onChannel}>📢 Kanal erstellen</button>
        <button type="button" disabled={busy} onClick={async () => { setBusy(true); try { onStarted(await e.openSelfChat()); } catch (x) { setErr((x as Error).message); } finally { setBusy(false); } }}>📝 Notizen an mich (Dateiablage)</button>
      </div>
    </Dialog>
  );
}

export function NewGroup({ onClose, onCreated }: { onClose: () => void; onCreated: (id: string) => void }) {
  const e = useEngine();
  const s = e.state!;
  const contacts = Object.values(s.conversations)
    .filter((c) => c.kind === 'dm' && c.status === 'active')
    .map((c) => c.members.find((m) => m.address !== s.me.address)?.address)
    .filter((a): a is string => !!a && !s.blockedUsers.includes(a));
  const [title, setTitle] = useState('');
  const [sel, setSel] = useState<string[]>([]);
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  return (
    <Dialog title="Neue Gruppe" onClose={onClose}>
      <label>Name<input value={title} onChange={(x) => setTitle(x.target.value)} maxLength={80} /></label>
      <div className="muted small">Mitglieder (nur bestehende Kontakte)</div>
      <div className="list">
        {contacts.length === 0 && <p className="muted">Noch keine Kontakte.</p>}
        {contacts.map((a) => (
          <label key={a} className="check">
            <input type="checkbox" checked={sel.includes(a)} onChange={(x) => setSel(x.target.checked ? [...sel, a] : sel.filter((y) => y !== a))} /> {a}
          </label>
        ))}
      </div>
      {err && <p className="error">{err}</p>}
      <button className="primary" disabled={busy || sel.length === 0} onClick={async () => {
        setBusy(true);
        setErr('');
        try { onCreated(await e.createGroup(title, sel)); } catch (x) { setErr((x as Error).message); } finally { setBusy(false); }
      }}>Gruppe erstellen</button>
    </Dialog>
  );
}
