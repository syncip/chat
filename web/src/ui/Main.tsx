import { useEffect, useState } from 'react';
import { decodeCard } from '../lib/engine';
import { isInsecureTransport } from '../lib/util';
import { useEngine } from './hooks';
import { ChatView } from './ChatView';
import { Settings } from './Settings';
import { BackupGate } from './BackupGate';
import { Dialog } from './Dialog';
import { StartChat, NewGroup } from './Dialogs';
import { ChannelView, CreateChannel, JoinChannel } from './Channels';

type Panel = null | 'settings' | 'new' | 'group' | 'channel' | 'join';

export function Main() {
  const e = useEngine();
  const s = e.state!;
  const [active, setActive] = useState<string | null>(null);
  const [panel, setPanel] = useState<Panel>(null);
  const [toast, setToast] = useState('');
  const [pending, setPending] = useState<string | null>(null);
  const [activeChan, setActiveChan] = useState<string | null>(null);
  const [joinLink, setJoinLink] = useState<string | null>(null);

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
  const currentChan = activeChan ? s.channels?.[activeChan] : undefined;

  return (
    <div className={`layout ${current || currentChan ? 'chat-open' : ''} ${isInsecureTransport() ? 'with-warning' : ''}`}>
      {isInsecureTransport() && (
        <div className="transport-warning" role="alert">
          ⚠ Unverschlüsselte Verbindung (http, kein TLS): Nachrichten bleiben Ende-zu-Ende verschlüsselt, aber ein Angreifer im Netzwerk
          kann die App selbst manipulieren und Schlüssel abgreifen. Nur in vertrauenswürdigen Netzen (LAN/VPN) nutzen.
        </div>
      )}
      <aside className="sidebar">
        <header>
          <div>
            <strong>{s.me.name}</strong>
            <div className="muted small">{e.online ? '● verbunden' : '○ offline'} · {s.me.domain}</div>
          </div>
          <div className="row">
            <button title="Neuer Chat" onClick={() => setPanel('new')}>＋</button>
            <button title="Neue Gruppe" onClick={() => setPanel('group')}>👥</button>
            <button title="Kanal erstellen" onClick={() => setPanel('channel')}>📢</button>
            <button title="Kanal beitreten" onClick={() => setPanel('join')}>🔗</button>
            <button title="Einstellungen" onClick={() => setPanel('settings')}>⚙</button>
          </div>
        </header>
        {requests.length > 0 && (
          <div className="requests">
            <div className="muted small">Anfragen</div>
            {requests.map((c) => (
              <button key={c.id} className="conv" onClick={() => setActive(c.id)}>
                <span className="title">{c.title}</span><span className="badge">neu</span>
              </button>
            ))}
          </div>
        )}
        <div className="convs">
          {chans.map((c) => (
            <button key={c.id} className={`conv ${c.id === activeChan ? 'active' : ''}`} onClick={() => { setActive(null); setActiveChan(c.id); e.channels.markRead(c.id); }}>
              <span className="title">📢 {c.title}</span>
              {c.unread > 0 && <span className="badge">{c.unread}</span>}
              <span className="preview muted small">{c.me.status === 'pending' ? 'Wartet auf Freigabe' : c.me.status === 'banned' ? 'Gesperrt' : 'Öffentlicher Kanal'}</span>
            </button>
          ))}
          {list.length === 0 && chans.length === 0 && <p className="muted pad">Noch keine Chats. Teile deinen Kontaktlink (⚙) oder öffne den Link eines Kontakts (＋).</p>}
          {list.map((c) => (
            <button key={c.id} className={`conv ${c.id === active ? 'active' : ''}`} onClick={() => { setActiveChan(null); setActive(c.id); e.markRead(c.id); }}>
              <span className="title">{c.kind === 'group' ? '👥 ' : ''}{c.title}</span>
              {c.unread > 0 && <span className="badge">{c.unread}</span>}
              <span className="preview muted small">{preview(c)}</span>
            </button>
          ))}
        </div>
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
      {!s.backupDone && <BackupGate />}
      {panel === 'settings' && <Settings onClose={() => setPanel(null)} />}
      {panel === 'new' && <StartChat onClose={() => setPanel(null)} onStarted={(id) => { setPanel(null); setActive(id); }} />}
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
      {toast && <div className="toast" role="status" onClick={() => setToast('')}>{toast}</div>}
      {e.notice && <div className="toast" role="status" onClick={() => { e.notice = ''; }}>{e.notice}</div>}
    </div>
  );
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
