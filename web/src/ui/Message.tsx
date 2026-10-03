import { Fragment, useState, type ReactNode } from 'react';
import type { Msg, Part } from '../lib/types';
import { formatBytes } from '../lib/util';
import { useEngine } from './hooks';

/** Sehr kleines, sicheres Markdown-Subset: `code`, **fett**, *kursiv*, https-Links. Niemals HTML. */
export function renderInline(text: string): ReactNode[] {
  const out: ReactNode[] = [];
  const re = /(`[^`\n]+`|\*\*[^*\n]+\*\*|\*[^*\n]+\*|https:\/\/[^\s<>"']+)/g;
  let last = 0;
  let i = 0;
  for (const m of text.matchAll(re)) {
    const idx = m.index ?? 0;
    if (idx > last) out.push(text.slice(last, idx));
    const t = m[0];
    if (t.startsWith('`')) out.push(<code key={i++}>{t.slice(1, -1)}</code>);
    else if (t.startsWith('**')) out.push(<strong key={i++}>{t.slice(2, -2)}</strong>);
    else if (t.startsWith('*')) out.push(<em key={i++}>{t.slice(1, -1)}</em>);
    else out.push(<a key={i++} href={t} target="_blank" rel="noopener noreferrer nofollow">{t}</a>);
    last = idx + t.length;
  }
  if (last < text.length) out.push(text.slice(last));
  return out;
}

function FilePart({ p }: { p: Extract<Part, { type: 'file' }> }) {
  const e = useEngine();
  const [state, setState] = useState<'idle' | 'loading' | 'error'>('idle');
  const [img, setImg] = useState<string | null>(null);
  const [err, setErr] = useState('');
  const isImg = /^image\/(png|jpeg|gif|webp)$/.test(p.mime);

  async function load(save: boolean) {
    setState('loading');
    setErr('');
    try {
      const blob = await e.downloadFile(p);
      const url = URL.createObjectURL(blob);
      if (save) {
        const a = document.createElement('a');
        a.href = url;
        a.download = p.name.replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_');
        a.rel = 'noopener';
        a.click();
        setTimeout(() => URL.revokeObjectURL(url), 10_000);
      } else setImg(url);
      setState('idle');
    } catch (x) {
      setErr((x as Error).message);
      setState('error');
    }
  }

  return (
    <div className="file">
      {img && <img src={img} alt={p.name} className="preview" />}
      <div className="file-row">
        <span className="file-name" title={p.name}>📎 {p.name}</span>
        <span className="muted">{formatBytes(p.size)}</span>
        {isImg && !img && <button className="link" disabled={state === 'loading'} onClick={() => load(false)}>Vorschau laden</button>}
        <button className="link" disabled={state === 'loading'} onClick={() => load(true)}>{state === 'loading' ? '…' : 'Speichern'}</button>
      </div>
      <div className="muted small">Wird von {p.blob_server} geladen · Dateien werden nie ausgeführt</div>
      {err && <div className="error small">{err}</div>}
    </div>
  );
}

export function MessageView({
  m, mine, onReply, onEdit, onDelete, onReact, quoted,
}: {
  m: Msg; mine: boolean; quoted?: string;
  onReply: () => void; onEdit: () => void; onDelete: () => void; onReact: (emoji: string) => void;
}) {
  const [menu, setMenu] = useState(false);
  if (m.deleted) return <div className={`msg ${mine ? 'mine' : ''}`}><div className="bubble deleted">Nachricht gelöscht</div></div>;
  return (
    <div className={`msg ${mine ? 'mine' : ''}`}>
      <div className="bubble">
        {!mine && <div className="sender">{m.from}</div>}
        {m.parts.map((p, i) => (
          <Fragment key={i}>
            {p.type === 'quote' && <blockquote title={quoted}>{p.snippet}</blockquote>}
            {p.type === 'text' && <p className="text">{renderInline(p.body)}</p>}
            {p.type === 'code' && (
              <pre className="code" data-lang={p.lang || undefined}>
                {p.lang && <span className="lang">{p.lang}</span>}
                <code>{p.body}</code>
                <button className="copy link" onClick={() => navigator.clipboard?.writeText(p.body)}>Kopieren</button>
              </pre>
            )}
            {p.type === 'file' && <FilePart p={p} />}
          </Fragment>
        ))}
        <div className="meta">
          {Object.entries(m.reactions).map(([emo, who]) => (
            <button key={emo} className="reaction" title={who.join(', ')} onClick={() => onReact(emo)}>{emo} {who.length}</button>
          ))}
          <span className="muted small">
            {new Date(m.ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
            {m.edited && ' · bearbeitet'}
            {m.expiresAt && ' · ⏱'}
            {mine && (m.status === 'sending' ? ' · sendet …' : m.status === 'failed' ? ' · fehlgeschlagen' : ' ✓')}
          </span>
          <button className="link small" aria-label="Aktionen" onClick={() => setMenu(!menu)}>⋯</button>
        </div>
        {menu && (
          <div className="menu">
            <button onClick={() => { setMenu(false); onReply(); }}>Antworten</button>
            {['👍', '❤️', '😂', '🎉'].map((e) => <button key={e} onClick={() => { setMenu(false); onReact(e); }}>{e}</button>)}
            {mine && <button onClick={() => { setMenu(false); onEdit(); }}>Bearbeiten</button>}
            {mine && <button onClick={() => { setMenu(false); onDelete(); }}>Löschen</button>}
          </div>
        )}
      </div>
    </div>
  );
}
