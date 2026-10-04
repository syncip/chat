import { Fragment, useEffect, useState, type ReactNode } from 'react';
import { AUDIO_RE, IMAGE_RE, VIDEO_RE } from '../lib/engine';
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

const AUTO_IMAGE_MAX = 15 * 1024 * 1024;

function fileIcon(name: string, mime: string): string {
  const ext = name.split('.').pop()?.toLowerCase() ?? '';
  if (mime.startsWith('audio/')) return '🎵';
  if (mime.startsWith('video/')) return '🎬';
  if (['pdf'].includes(ext)) return '📕';
  if (['doc', 'docx', 'odt', 'rtf', 'txt', 'md'].includes(ext)) return '📄';
  if (['xls', 'xlsx', 'csv', 'ods'].includes(ext)) return '📊';
  if (['zip', '7z', 'rar', 'tar', 'gz'].includes(ext)) return '🗜️';
  if (['exe', 'msi', 'apk', 'bat', 'sh', 'dll'].includes(ext)) return '⚙️';
  return '📎';
}

export function saveBlobUrl(url: string, name: string) {
  const a = document.createElement('a');
  a.href = url;
  a.download = name.replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_');
  a.rel = 'noopener';
  a.click();
}

/** Datei in einer Nachricht: Bilder werden direkt angezeigt, Audio/Video per Klick abgespielt, alles andere als Datei-Karte. */
export function FilePart({ p, compact = false }: { p: Extract<Part, { type: 'file' }>; compact?: boolean }) {
  const e = useEngine();
  const kind = IMAGE_RE.test(p.mime) ? 'image' : AUDIO_RE.test(p.mime) ? 'audio' : VIDEO_RE.test(p.mime) ? 'video' : 'file';
  const [url, setUrl] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const [zoom, setZoom] = useState(false);

  async function load(): Promise<string | null> {
    setBusy(true);
    setErr('');
    try {
      const u = await e.mediaUrl(p);
      setUrl(u);
      return u;
    } catch (x) {
      setErr((x as Error).message);
      return null;
    } finally {
      setBusy(false);
    }
  }
  async function save() {
    const u = url ?? (await load());
    if (u) saveBlobUrl(u, p.name);
  }

  useEffect(() => {
    if (kind === 'image' && p.size <= AUTO_IMAGE_MAX) void load();
  }, [p.blob_id]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className={`file ${kind}`}>
      {kind === 'image' && (
        url ? <img src={url} alt={p.name} className="preview media-img" onClick={() => setZoom(true)} />
          : <div className="media-ph" aria-busy={busy}>{busy ? 'Lädt …' : <button className="link" onClick={() => load()}>Bild laden ({formatBytes(p.size)})</button>}</div>
      )}
      {kind === 'video' && (url ? <video src={url} controls autoPlay className="media-video" /> : (
        <button className="media-play" disabled={busy} onClick={() => load()}>{busy ? 'Lädt …' : '▶ Video abspielen'}</button>
      ))}
      {kind === 'audio' && (url ? <audio src={url} controls autoPlay className="media-audio" /> : (
        <button className="media-play audio" disabled={busy} onClick={() => load()}>{busy ? 'Lädt …' : '▶ Audio abspielen'}</button>
      ))}
      <div className="file-row">
        <span className="file-icon" aria-hidden="true">{fileIcon(p.name, p.mime)}</span>
        <span className="file-name" title={p.name}>{p.name}</span>
        <span className="muted small">{formatBytes(p.size)}</span>
        <button className="link" disabled={busy} onClick={save}>{busy ? '…' : 'Speichern'}</button>
      </div>
      {!compact && <div className="muted small">Von {p.blob_server} geladen und lokal entschlüsselt · Dateien werden nie ausgeführt</div>}
      {err && <div className="error small">{err}</div>}
      {zoom && url && (
        <div className="lightbox" onClick={() => setZoom(false)} role="dialog" aria-label={p.name}>
          <img src={url} alt={p.name} />
          <button className="lightbox-close" aria-label="Schließen">✕</button>
        </div>
      )}
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
