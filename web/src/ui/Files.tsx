import { useEffect, useRef, useState, type ReactNode } from 'react';
import { AUDIO_RE, IMAGE_RE, VIDEO_RE } from '../lib/engine';
import type { AppState, Part } from '../lib/types';
import { useEngine } from './hooks';
import { Dialog } from './Dialog';
import { FilePart } from './Message';

type FileParty = Extract<Part, { type: 'file' }>;
export interface FileItem {
  part: FileParty;
  from: string;
  ts: number;
  convId: string;
  convTitle: string;
  msgId: string;
}

/** Alle Dateien aus den Chats (oder nur einem Chat), neueste zuerst. Einmal-Nachrichten und gelöschte Nachrichten zählen nicht. */
export function collectFiles(s: AppState, convId?: string): FileItem[] {
  const out: FileItem[] = [];
  for (const c of Object.values(s.conversations)) {
    if (convId && c.id !== convId) continue;
    for (const m of c.messages) {
      if (m.deleted || m.once) continue;
      for (const p of m.parts) if (p.type === 'file') out.push({ part: p, from: m.from, ts: m.ts, convId: c.id, convTitle: c.title, msgId: m.id });
    }
  }
  return out.sort((a, b) => b.ts - a.ts);
}

type Tab = 'all' | 'images' | 'media' | 'docs';
const kindOf = (p: FileParty): Tab => (IMAGE_RE.test(p.mime) ? 'images' : AUDIO_RE.test(p.mime) || VIDEO_RE.test(p.mime) ? 'media' : 'docs');

/** Rendert die Kinder erst, wenn das Element sichtbar wird (Bilder werden dadurch nicht alle gleichzeitig geladen). */
function Lazy({ children, minHeight = 80 }: { children: ReactNode; minHeight?: number }) {
  const ref = useRef<HTMLDivElement>(null);
  const [seen, setSeen] = useState(false);
  useEffect(() => {
    const el = ref.current;
    if (!el || seen) return;
    const io = new IntersectionObserver((es) => { if (es.some((x) => x.isIntersecting)) { setSeen(true); io.disconnect(); } }, { rootMargin: '200px' });
    io.observe(el);
    return () => io.disconnect();
  }, [seen]);
  return <div ref={ref} style={seen ? undefined : { minHeight }}>{seen ? children : null}</div>;
}

export function FilesDialog({ convId, onClose }: { convId?: string; onClose: () => void }) {
  const e = useEngine();
  const all = collectFiles(e.state!, convId);
  const [tab, setTab] = useState<Tab>('all');
  const [q, setQ] = useState('');
  const shown = all.filter((f) => (tab === 'all' || kindOf(f.part) === tab) && (!q || f.part.name.toLowerCase().includes(q.toLowerCase())));
  const count = (t: Tab) => all.filter((f) => t === 'all' || kindOf(f.part) === t).length;
  const tabs: [Tab, string][] = [['all', 'Alle'], ['images', 'Bilder'], ['media', 'Audio & Video'], ['docs', 'Dokumente']];
  const grid = tab === 'images';
  return (
    <Dialog title={convId ? 'Dateien in diesem Chat' : 'Alle Dateien'} onClose={onClose} wide>
      <div className="tabs" role="tablist">
        {tabs.map(([t, label]) => (
          <button key={t} role="tab" aria-selected={tab === t} className={tab === t ? 'on' : ''} onClick={() => setTab(t)}>{label} <span className="muted small">{count(t)}</span></button>
        ))}
      </div>
      <input type="search" placeholder="Dateiname suchen …" value={q} onChange={(x) => setQ(x.target.value)} aria-label="Dateien durchsuchen" />
      {shown.length === 0 && <p className="muted pad">Keine Dateien.</p>}
      <div className={grid ? 'files-grid' : 'files-list'}>
        {shown.map((f) => (
          <div key={`${f.msgId}-${f.part.blob_id}`} className="files-item">
            <Lazy minHeight={grid ? 120 : 60}><FilePart p={f.part} compact /></Lazy>
            <div className="muted small">{f.from.split('@')[0]}{convId ? '' : ` · ${f.convTitle}`} · {new Date(f.ts).toLocaleDateString()}</div>
          </div>
        ))}
      </div>
    </Dialog>
  );
}
