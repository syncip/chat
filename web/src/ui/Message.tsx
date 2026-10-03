import { Fragment, useState, type ReactNode } from 'react';
import type { Msg, Part } from '../lib/types';
import { copyText, formatBytes } from '../lib/util';
import { useEngine } from './hooks';
import { Dialog } from './Dialog';

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

function CodeBlock({ p }: { p: Extract<Part, { type: 'code' }> }) {
  const lines = p.body.split('\n').length;
  const [open, setOpen] = useState(lines <= 10); // lange Blöcke starten eingeklappt
  return (
    <details className="codebox" open={open} onToggle={(e) => setOpen((e.currentTarget as HTMLDetailsElement).open)}>
      <summary>
        Code{p.lang ? ` · ${p.lang}` : ''} · {lines} {lines === 1 ? 'Zeile' : 'Zeilen'}
      </summary>
      <pre className="code" data-lang={p.lang || undefined}>
        <code>{p.body}</code>
        <button className="copy link" onClick={() => copyText(p.body)}>Kopieren</button>
      </pre>
    </details>
  );
}

export function Parts({ parts }: { parts: Part[] }) {
  return (
    <>
      {parts.map((p, i) => (
        <Fragment key={i}>
          {p.type === 'quote' && <blockquote>{p.snippet}</blockquote>}
          {p.type === 'text' && <p className="text">{renderInline(p.body)}</p>}
          {p.type === 'code' && <CodeBlock p={p} />}
          {p.type === 'file' && <FilePart p={p} />}
        </Fragment>
      ))}
    </>
  );
}

/** Statusanzeige eigener Nachrichten. Delivered/read sieht nur, wer selbst die jeweilige Bestätigung sendet. */
export function statusLabel(m: Msg, showDelivered: boolean, showRead: boolean): string {
  if (m.status === 'sending') return ' · sendet …';
  if (m.status === 'failed') return ' · fehlgeschlagen';
  let st = m.status;
  if (st === 'read' && !showRead) st = showDelivered ? 'delivered' : 'sent';
  if (st === 'delivered' && !showDelivered) st = 'sent';
  return st === 'read' ? ' ✓✓ gelesen' : st === 'delivered' ? ' ✓✓ empfangen' : ' ✓ gesendet';
}

function OnceBubble({ convId, m }: { convId: string; m: Msg }) {
  const e = useEngine();
  const [shown, setShown] = useState<Part[] | null>(null);
  if (m.consumed && !shown) return <div className="muted">🔒 Einmal-Nachricht gelesen</div>;
  return (
    <>
      <button onClick={() => setShown(e.revealOnce(convId, m.id))}>🔒 Einmal-Nachricht anzeigen</button>
      <div className="muted small">Wird nach dem Anzeigen gelöscht und dem Absender als gelesen gemeldet.</div>
      {shown && (
        <Dialog title="Einmal-Nachricht" onClose={() => setShown(null)}>
          <Parts parts={shown} />
          <p className="warn">Diese Nachricht ist nur jetzt sichtbar. Beim Schließen ist sie unwiderruflich gelöscht.</p>
          <button className="primary" onClick={() => setShown(null)}>Schließen und löschen</button>
        </Dialog>
      )}
    </>
  );
}

export function MessageView({
  m, mine, onReply, onEdit, onDelete, onReact, convId, showDelivered, showRead,
}: {
  m: Msg; mine: boolean; convId: string; showDelivered: boolean; showRead: boolean;
  onReply: () => void; onEdit: () => void; onDelete: () => void; onReact: (emoji: string) => void;
}) {
  const [menu, setMenu] = useState(false);
  if (m.deleted) return <div className={`msg ${mine ? 'mine' : ''}`}><div className="bubble deleted">Nachricht gelöscht</div></div>;
  return (
    <div className={`msg ${mine ? 'mine' : ''}`}>
      <div className="bubble">
        {!mine && <div className="sender">{m.from}</div>}
        {m.once && !mine ? (
          <OnceBubble convId={convId} m={m} />
        ) : (
          <>
            {m.once && mine && <div className="muted small">🔒 Einmal-Nachricht{m.consumed ? ' (eigene Kopie entfernt)' : ''}</div>}
            <Parts parts={m.parts} />
          </>
        )}
        <div className="meta">
          {Object.entries(m.reactions).map(([emo, who]) => (
            <button key={emo} className="reaction" title={who.join(', ')} onClick={() => onReact(emo)}>{emo} {who.length}</button>
          ))}
          <span className="muted small">
            {new Date(m.ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
            {m.edited && ' · bearbeitet'}
            {m.expiresAt && ' · ⏱'}
            {mine && statusLabel(m, showDelivered, showRead)}
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
