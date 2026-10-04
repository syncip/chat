import { useEffect, useRef, useState } from 'react';
import type { Part } from '../lib/types';
import { baseUrl, dec, unb64 } from '../lib/util';
import { Parts } from './Message';
import { Avatar } from './Avatar';

interface Entry {
  seq: number;
  type: string;
  ts: number;
  address: string;
  post_id?: string;
  deleted?: boolean;
  hook?: string;
  data?: string;
  kind?: string;
  meta?: string;
}

interface Post {
  id: string;
  seq: number;
  ts: number;
  from: string;
  hook?: string;
  parts: Part[];
  deleted: boolean;
}

export function parsePublicLink(hash: string): { s: string; c: string } | null {
  const m = /^#\/c\/([A-Za-z0-9_-]+)$/.exec(hash);
  if (!m) return null;
  try {
    let p = m[1].replace(/-/g, '+').replace(/_/g, '/');
    while (p.length % 4) p += '=';
    const j = JSON.parse(dec.decode(unb64(p))) as { s: string; c: string };
    return j.s && j.c ? j : null;
  } catch {
    return null;
  }
}

/** Öffentlicher Kanal ohne Konto: liest den unverschlüsselten Kanal und folgt neuen Beiträgen live (Server-Sent Events). */
export function PublicChannelView({ link, onClose }: { link: { s: string; c: string }; onClose: () => void }) {
  const [title, setTitle] = useState('');
  const [posts, setPosts] = useState<Post[]>([]);
  const [err, setErr] = useState('');
  const [live, setLive] = useState(false);
  const seen = useRef(new Set<number>());
  const bottom = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const base = `${baseUrl(link.s)}/v1/channels/${encodeURIComponent(link.c)}`;
    let es: EventSource | null = null;
    let stop = false;
    const add = (e: Entry) => {
      if (seen.current.has(e.seq)) return;
      seen.current.add(e.seq);
      if (e.type === 'event') {
        if (e.kind === 'delete') {
          let id = '';
          try { id = (JSON.parse(e.meta || '{}') as { post_id?: string }).post_id ?? ''; } catch { /* leer */ }
          setPosts((ps) => ps.map((p) => (p.id === id ? { ...p, deleted: true, parts: [] } : p)));
        }
        return;
      }
      let parts: Part[] = [];
      if (!e.deleted && e.data) {
        try { parts = (JSON.parse(dec.decode(unb64(e.data))) as { parts: Part[] }).parts ?? []; } catch { parts = [{ type: 'text', body: '(Beitrag nicht lesbar)' }]; }
      }
      setPosts((ps) => [...ps, { id: e.post_id ?? String(e.seq), seq: e.seq, ts: e.ts, from: e.address, hook: e.hook, parts, deleted: !!e.deleted }]);
    };
    (async () => {
      try {
        const r = await fetch(`${base}/public/log?limit=200`, { referrerPolicy: 'no-referrer', credentials: 'omit', cache: 'no-store' });
        if (!r.ok) throw new Error(r.status === 404 ? 'Kanal nicht gefunden oder nicht öffentlich.' : `Fehler ${r.status}`);
        const j = (await r.json()) as { title: string; entries: Entry[] };
        if (stop) return;
        setTitle(j.title);
        j.entries.forEach(add);
        const last = j.entries.at(-1)?.seq ?? 0;
        es = new EventSource(`${base}/public/events?after=${last}`);
        es.onopen = () => setLive(true);
        es.onerror = () => setLive(false);
        es.onmessage = (ev) => add(JSON.parse(ev.data) as Entry);
      } catch (x) {
        setErr((x as Error).message || 'Verbindung fehlgeschlagen');
      }
    })();
    return () => { stop = true; es?.close(); };
  }, [link.s, link.c]);

  useEffect(() => { bottom.current?.scrollIntoView({ block: 'end' }); }, [posts.length]);

  return (
    <div className="app">
      <div className="alert-bar" role="note">
        <span className="grow">🌐 <strong>Öffentlicher Kanal</strong> – alle Inhalte sind unverschlüsselt und für jeden sichtbar. Schreibe hier nichts Vertrauliches.</span>
      </div>
      <div className="pubview">
        <header className="chat-header">
          <Avatar name={title || link.c} size={40} channel />
          <div className="grow"><strong>📢 {title || 'Kanal'}</strong><div className="muted small">{link.s} · {live ? '● live' : '○ nicht verbunden'} · du liest ohne Konto mit</div></div>
          <button onClick={() => { history.replaceState(null, '', location.pathname); onClose(); }}>Zur Chat-App</button>
        </header>
        {err && <p className="error pad">{err}</p>}
        <div className="messages">
          {posts.length === 0 && !err && <p className="muted pad">Noch keine Beiträge.</p>}
          {posts.map((p) => (
            <div key={p.id} className="msg theirs">
              <div className="bubble">
                <div className="sender">{p.hook ? `🔔 ${p.hook}` : p.from}</div>
                {p.deleted ? <em className="muted">Beitrag entfernt</em> : <Parts parts={p.parts} />}
                <div className="meta small muted">{new Date(p.ts).toLocaleString()}</div>
              </div>
            </div>
          ))}
          <div ref={bottom} />
        </div>
        <div className="banner">Mitschreiben kannst du mit einem Konto auf diesem oder einem anderen Server (Kanal beitreten).</div>
      </div>
    </div>
  );
}

