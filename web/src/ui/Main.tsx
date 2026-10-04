import { useEffect, useState } from 'react';
import { decodeCard } from '../lib/engine';
import { isInsecureTransport } from '../lib/util';
import { useEngine } from './hooks';
import { ChatView } from './ChatView';
import { Settings } from './Settings';
import { BackupGate } from './BackupGate';
import { Dialog } from './Dialog';
import { AddDialog, NewGroup } from './Dialogs';
import { Avatar } from './Avatar';
import { FilesDialog } from './Files';
import { SecurityDialog } from './Security';
import { securityReport } from '../lib/security';
import { AdminDialog } from './Admin';
import { idleLockMinutes } from '../lib/prefs';
import { ChannelView, CreateChannel, JoinChannel } from './Channels';
import { Icon } from './Icon';
import type { ChannelState } from '../lib/types';
import { clearSession } from '../lib/session';

type Panel = null | 'admin' | 'settings' | 'new' | 'group' | 'channel' | 'join' | 'files' | 'security';

export function Main() {
  const e = useEngine();
  const s = e.state!;
  const [active, setActive] = useState<string | null>(null);
  const [panel, setPanel] = useState<Panel>(null);
  const [toast, setToast] = useState('');
  const [, force] = useState(0);
  // Automatische Sperre nach Inaktivität (Einstellung dieses Geräts)
  useEffect(() => {
    let last = Date.now();
    const bump = () => { last = Date.now(); };
    const evs = ['mousemove', 'keydown', 'pointerdown', 'touchstart', 'scroll'] as const;
    evs.forEach((n) => window.addEventListener(n, bump, { passive: true }));
    const t = setInterval(() => {
      const m = idleLockMinutes();
      if (m > 0 && Date.now() - last > m * 60_000) void e.lock();
    }, 10_000);
    return () => { evs.forEach((n) => window.removeEventListener(n, bump)); clearInterval(t); };
  }, [e]);
  const [httpDismissed, setHttpDismissed] = useState(() => { try { return sessionStorage.getItem('httpWarn') === '1'; } catch { return false; } });
  const [pending, setPending] = useState<string | null>(null);
  const [activeChan, setActiveChan] = useState<string | null>(null);
  const [joinLink, setJoinLink] = useState<string | null>(null);
  const [searching, setSearching] = useState(false);
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<'all' | 'chats' | 'channels'>('all');

  // Kontaktlink im URL-Fragment (#/add/…) öffnen.
  useEffect(() => {
    const check = () => {
      if (location.hash.startsWith('#/add/')) {
        setPending(location.hash);
        history.replaceState(null, '', location.pathname);
      } else if (location.hash.startsWith('#/join/')) {
        setJoinLink(location.hash);
        history.replaceState(null, '', location.pathname);
      }
    };
    check();
    window.addEventListener('hashchange', check);
    return () => window.removeEventListener('hashchange', check);
  }, []);

  const convs = Object.values(s.conversations).sort(
    (a, b) => (b.messages.at(-1)?.ts ?? b.createdAt) - (a.messages.at(-1)?.ts ?? a.createdAt),
  );
  const requests = convs.filter((c) => c.status === 'request');
  const list = convs.filter((c) => c.status !== 'request');
  const current = active ? s.conversations[active] : undefined;
  const chans = Object.values(s.channels ?? {}).sort((a, b) => (b.posts.at(-1)?.ts ?? b.createdAt) - (a.posts.at(-1)?.ts ?? a.createdAt));
  const sec = securityReport(e);
  const alerts = s.alerts ?? [];
  const unreadTotal = Object.values(s.conversations).reduce((n, c) => n + c.unread, 0) + Object.values(s.channels ?? {}).reduce((n, c) => n + c.unread, 0);
  useEffect(() => { document.title = unreadTotal > 0 ? `(${unreadTotal}) Chat` : 'Chat'; }, [unreadTotal]);
  const currentChan = activeChan ? s.channels?.[activeChan] : undefined;
  const q = query.trim().toLowerCase();
  const shownConvs = q ? list.filter((c) => c.title.toLowerCase().includes(q)) : list;
  const shownChans = q ? chans.filter((c) => c.title.toLowerCase().includes(q)) : chans;
  const unreadChats = Object.values(s.conversations).reduce((n, c) => n + c.unread, 0);
  const unreadChans = Object.values(s.channels ?? {}).reduce((n, c) => n + c.unread, 0);

  return (
    <div className="app">
      {isInsecureTransport() && !httpDismissed && (
        <div className="transport-warning" role="alert">
          <span className="grow">⚠ Unverschlüsselte Verbindung (http, kein TLS): Nachrichten bleiben Ende-zu-Ende verschlüsselt, aber ein Angreifer im Netzwerk
          kann die App selbst manipulieren und Schlüssel abgreifen. Nur in vertrauenswürdigen Netzen (LAN/VPN) nutzen.</span>
          <button className="bar-close" aria-label="Hinweis schließen" title="Schließen" onClick={() => { setHttpDismissed(true); try { sessionStorage.setItem('httpWarn', '1'); } catch { /* egal */ } }}>✕</button>
        </div>
      )}
      {alerts.length > 0 && (
        <div className="alert-bar" role="alert">
          <span className="grow">⚠ Sicherheitshinweis: {alerts[0].text}{alerts.length > 1 ? ` (+${alerts.length - 1} weitere)` : ''}</span>
          <button onClick={() => setPanel('security')}>Ansehen</button>
          <button className="bar-close" aria-label="Hinweis schließen" title="Als gesehen markieren und schließen" onClick={() => alerts.forEach((a) => e.dismissAlert(a.id))}>✕</button>
        </div>
      )}
    <div className={`layout ${current || currentChan ? 'chat-open' : ''}`}>
      <aside className="sidebar">
        <header className="appbar">
          <Avatar name={s.me.address} src={s.me.avatar} size={40} />
          <div className="appbar-title">
            <strong>{s.me.name}</strong>
            <div className="muted small" title={s.me.address}>{e.online ? '● verbunden' : '○ offline'}</div>
          </div>
          <div className="row icons">
            <button className="icon-btn" title="Suchen" aria-label="Suchen" onClick={() => { setSearching(!searching); setQuery(''); }}><Icon name="search" /></button>
            <button className="icon-btn" title="Alle Dateien" aria-label="Alle Dateien" onClick={() => setPanel('files')}><Icon name="folder" /></button>
            {e.isAdmin && <button className="icon-btn" title="Server-Administration" aria-label="Administration" onClick={() => setPanel('admin')}><Icon name="admin" /></button>}
            <button title="Sicherheit" aria-label="Sicherheit" className={`icon-btn sec-chip ${sec.level}`} onClick={() => setPanel('security')}><Icon name="shield" /></button>
            <button className="icon-btn" title="Sperren" aria-label="Sperren" onClick={() => { void clearSession(); void e.lock(); }}><Icon name="lock" /></button>
            <button className="icon-btn" title="Einstellungen" aria-label="Einstellungen" onClick={() => setPanel('settings')}><Icon name="settings" /></button>
          </div>
        </header>
        {searching && (
          <div className="searchbar">
            <Icon name="search" size={18} />
            <input autoFocus aria-label="Chats durchsuchen" placeholder="Suchen …" value={query} onChange={(x) => setQuery(x.target.value)} />
          </div>
        )}
        <div className="chips" role="tablist" aria-label="Filter">
          {([['all', 'Alle', unreadChats + unreadChans], ['chats', 'Chats', unreadChats], ['channels', 'Kanäle', unreadChans]] as const).map(([k, label, n]) => (
            <button key={k} role="tab" aria-selected={filter === k} className={`chip-btn ${filter === k ? 'on' : ''}`} onClick={() => setFilter(k)}>
              {label}{n > 0 && <span className="badge">{n}</span>}
            </button>
          ))}
        </div>
        {requests.length > 0 && filter !== 'channels' && (
          <div className="requests">
            <div className="muted small">Anfragen</div>
            {requests.map((c) => (
              <button key={c.id} className="conv" onClick={() => setActive(c.id)}>
                <Avatar name={c.title} src={e.avatarOfConv(c)} />
                <span className="conv-main"><span className="title">{c.title}</span><span className="preview muted small">möchte mit dir chatten</span></span>
                <span className="conv-side"><span className="badge">neu</span></span>
              </button>
            ))}
          </div>
        )}
        <div className="convs">
          {filter !== 'chats' && shownChans.map((c) => (
            <button key={c.id} className={`conv ${c.id === activeChan ? 'active' : ''}`} onClick={() => { setActive(null); setActiveChan(c.id); e.channels.markRead(c.id); }}>
              <Avatar name={c.title} channel src={c.avatar} />
              <span className="conv-main">
                <span className="title"><Icon name="channel" size={15} /> {c.title}</span>
                <span className="preview muted small">{c.me.status === 'pending' ? 'Wartet auf Freigabe' : c.me.status === 'banned' ? 'Gesperrt' : chanPreview(c)}</span>
              </span>
              <span className="conv-side">
                <span className="time muted small">{c.posts.at(-1) ? timeShort(c.posts.at(-1)!.ts) : ''}</span>
                {c.unread > 0 && <span className="badge">{c.unread}</span>}
              </span>
            </button>
          ))}
          {list.length === 0 && chans.length === 0 && <p className="muted pad empty">Noch keine Chats. Tippe auf <strong>＋</strong> und füge den Link oder Chat-Code eines Kontakts ein – oder lege eine Gruppe, einen Kanal oder „Notizen an mich“ an.</p>}
          {filter !== 'channels' && shownConvs.map((c) => (
            <button key={c.id} className={`conv ${c.id === active ? 'active' : ''}`} onClick={() => { setActiveChan(null); setActive(c.id); e.markRead(c.id); }}>
              <Avatar name={c.title} src={e.avatarOfConv(c)} />
              <span className="conv-main">
                <span className="title">{c.kind === 'group' && <><Icon name="group" size={15} /> </>}{c.title}</span>
                <span className="preview muted small">{preview(c)}</span>
              </span>
              <span className="conv-side">
                <span className="time muted small">{c.messages.at(-1) ? timeShort(c.messages.at(-1)!.ts) : ''}</span>
                {c.unread > 0 && <span className="badge">{c.unread}</span>}
              </span>
            </button>
          ))}
        </div>
        <button className="fab" title="Hinzufügen" aria-label="Neu" onClick={() => setPanel('new')}><Icon name="add" size={26} /></button>
      </aside>
      <main>
        {currentChan ? (
          <ChannelView key={currentChan.id} ch={currentChan} onBack={() => setActiveChan(null)} onGone={() => setActiveChan(null)} />
        ) : current ? (
          <ChatView key={current.id} conv={current} onBack={() => setActive(null)} onClosed={() => setActive(null)} />
        ) : (
          <div className="center muted">Wähle einen Chat oder starte einen neuen.</div>
        )}
      </main>
    </div>
      {!s.backupDone && <BackupGate />}
      {panel === 'admin' && <AdminDialog onClose={() => setPanel(null)} />}
      {panel === 'files' && <FilesDialog onClose={() => setPanel(null)} />}
      {panel === 'security' && <SecurityDialog onClose={() => setPanel(null)} />}
      {panel === 'settings' && <Settings onClose={() => setPanel(null)} />}
      {panel === 'new' && <AddDialog onClose={() => setPanel(null)} onStarted={(id) => { setPanel(null); setActiveChan(null); setActive(id); }} onGroup={() => setPanel('group')} onChannel={() => setPanel('channel')} onJoinLink={(h) => { setPanel(null); setJoinLink(h); }} />}
      {panel === 'group' && <NewGroup onClose={() => setPanel(null)} onCreated={(id) => { setPanel(null); setActive(id); }} />}
      {panel === 'channel' && <CreateChannel onClose={() => setPanel(null)} onCreated={(id) => { setPanel(null); setActive(null); setActiveChan(id); }} />}
      {(panel === 'join' || joinLink) && (
        <JoinChannel key={joinLink ?? 'manual'} initial={joinLink ?? undefined} onClose={() => { setPanel(null); setJoinLink(null); }}
          onJoined={(id) => { setPanel(null); setJoinLink(null); setActive(null); setActiveChan(id); }} />
      )}
      {pending && (
        <Dialog title="Kontakt hinzufügen" onClose={() => setPending(null)}>
          <PendingCard link={pending} onDone={(id) => { setPending(null); if (id) setActive(id); }} onError={setToast} />
        </Dialog>
      )}
      {toast && <div className="toast" role="status"><span>{toast}</span><button className="bar-close" aria-label="Meldung schließen" onClick={() => setToast('')}>✕</button></div>}
      {e.notice && <div className="toast" role="status"><span>{e.notice}</span><button className="bar-close" aria-label="Meldung schließen" onClick={() => { e.notice = ''; force((n) => n + 1); }}>✕</button></div>}
    </div>
  );
}

function timeShort(ts: number): string {
  const d = new Date(ts);
  const today = new Date();
  return d.toDateString() === today.toDateString() ? d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : d.toLocaleDateString([], { day: '2-digit', month: '2-digit' });
}

function chanPreview(c: ChannelState): string {
  const p = c.posts.at(-1);
  if (!p) return c.key ? 'Privater Kanal' : 'Öffentlicher Kanal';
  if (p.deleted) return 'Beitrag gelöscht';
  return preview({ status: '', messages: [p] });
}

function preview(c: { messages: { parts: { type: string; body?: string; name?: string }[]; deleted?: boolean; once?: boolean }[]; status: string }): string {
  if (c.status === 'left') return 'Verlassen';
  const m = c.messages.at(-1);
  if (!m) return '';
  if (m.deleted) return 'Nachricht gelöscht';
  if (m.once) return '🔒 Einmal-Nachricht';
  const p = m.parts.find((x) => x.type === 'text' || x.type === 'code' || x.type === 'file');
  if (!p) return '';
  return p.type === 'file' ? `📎 ${p.name}` : (p.body ?? '').slice(0, 60);
}

function PendingCard({ link, onDone, onError }: { link: string; onDone: (id?: string) => void; onError: (m: string) => void }) {
  const e = useEngine();
  const [busy, setBusy] = useState(false);
  let card;
  try {
    card = decodeCard(link);
  } catch (x) {
    return <p className="error">{(x as Error).message}</p>;
  }
  return (
    <>
      <p>Chat mit <strong>{card.address}</strong> starten?</p>
      <p className="muted small">Verifiziere den Kontakt später über die Sicherheitsnummer.</p>
      <div className="row">
        <button disabled={busy} className="primary" onClick={async () => {
          setBusy(true);
          try { onDone(await e.startChat(card)); } catch (x) { onError((x as Error).message); onDone(); }
        }}>Chat starten</button>
        <button onClick={() => onDone()}>Abbrechen</button>
      </div>
    </>
  );
}
