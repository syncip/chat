import { useEffect, useRef, useState } from 'react';
import type { Conversation, Msg } from '../lib/types';
import { memberAddresses, snippetOf } from '../lib/engine';
import { formatBytes } from '../lib/util';
import { useEngine } from './hooks';
import { MessageView } from './Message';
import { Dialog } from './Dialog';
import { Avatar } from './Avatar';
import { FilesDialog } from './Files';
import { convSecurity } from '../lib/security';

export function ChatView({ conv, onBack, onClosed }: { conv: Conversation; onBack: () => void; onClosed: () => void }) {
  const e = useEngine();
  const me = e.state!.me.address;
  const [text, setText] = useState('');
  const [codeMode, setCodeMode] = useState(false);
  const [once, setOnce] = useState(false);
  const [lang, setLang] = useState('');
  const [files, setFiles] = useState<File[]>([]);
  const [reply, setReply] = useState<Msg | null>(null);
  const [editing, setEditing] = useState<Msg | null>(null);
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  const [info, setInfo] = useState(false);
  const [filesOpen, setFilesOpen] = useState(false);
  const bottom = useRef<HTMLDivElement>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  useEffect(() => { bottom.current?.scrollIntoView({ block: 'end' }); e.markRead(conv.id); }, [conv.messages.length, conv.id, e]);

  async function send() {
    setErr('');
    setBusy(true);
    try {
      if (editing) {
        await e.editMessage(conv.id, editing.id, text);
        setEditing(null);
      } else {
        await e.sendMessage(conv.id, {
          text: codeMode ? undefined : text,
          code: codeMode ? { lang, body: text } : undefined,
          quote: reply ?? undefined,
          files,
          once: once && conv.kind === 'dm',
        });
      }
      setText('');
      setFiles([]);
      setReply(null);
      setCodeMode(false);
      setOnce(false);
    } catch (x) {
      setErr((x as Error).message);
    } finally {
      setBusy(false);
    }
  }


  if (conv.status === 'request') {
    return (
      <div className="chat">
        <ChatHeader conv={conv} onBack={onBack} onInfo={() => setInfo(true)} onFiles={() => setFilesOpen(true)} />
        <div className="messages">
          <div className="card pad">
            <p><strong>{conv.title}</strong> möchte mit dir chatten.</p>
            <p className="muted small">Erst nach dem Annehmen erfährt dein Gegenüber, wo es dich erreicht. Ablehnen verwirft alles, ohne dass es benachrichtigt wird.</p>
            <div className="row">
              <button className="primary" onClick={() => e.acceptRequest(conv.id)}>Annehmen</button>
              <button onClick={async () => { await e.declineRequest(conv.id); onClosed(); }}>Ablehnen</button>
              {conv.kind === 'dm' && <button onClick={async () => { await e.declineRequest(conv.id); await e.blockUser(conv.members.find((m) => m.address !== me)!.address); onClosed(); }}>Blockieren</button>}
            </div>
          </div>
          {conv.messages.map((m) => <MessageView key={m.id} m={m} mine={false} convId={conv.id} showDelivered={false} showRead={false} onReply={() => {}} onEdit={() => {}} onDelete={() => {}} onReact={() => {}} />)}
        </div>
      </div>
    );
  }

  const active = conv.status === 'active';
  return (
    <div className="chat">
      <ChatHeader conv={conv} onBack={onBack} onInfo={() => setInfo(true)} onFiles={() => setFilesOpen(true)} />
      {conv.warning && (
        <div className="banner warn" role="alert">
          <span className="grow">⚠ {conv.warning}</span>
          <button className="link" onClick={() => setInfo(true)}>Prüfen</button>
          <button className="bar-close" aria-label="Warnung ausblenden" title="Ausblenden (bis zur nächsten Änderung)" onClick={() => e.dismissConvWarning(conv.id)}>✕</button>
        </div>
      )}
      <div className="messages">
        {conv.messages.map((m) => (
          <MessageView
            key={m.id} m={m} mine={m.from === me} convId={conv.id}
            showDelivered={e.state!.sendDelivered} showRead={e.state!.sendRead}
            onReply={() => setReply(m)}
            onEdit={() => { setEditing(m); setText(m.parts.find((p) => p.type === 'text')?.body ?? ''); setCodeMode(false); }}
            onDelete={() => e.deleteMessage(conv.id, m.id)}
            onReact={(emo) => e.react(conv.id, m.id, emo)}
          />
        ))}
        <div ref={bottom} />
      </div>
      {active ? (
        <div className="composer">
          {reply && <div className="reply">Antwort auf: {snippetOf(reply).slice(0, 80)} <button className="link" onClick={() => setReply(null)}>✕</button></div>}
          {editing && <div className="reply">Nachricht bearbeiten <button className="link" onClick={() => { setEditing(null); setText(''); }}>✕</button></div>}
          {files.length > 0 && (
            <div className="files">
              {files.map((f, i) => <span key={i} className="chip">{f.name} ({formatBytes(f.size)}) <button className="link" onClick={() => setFiles(files.filter((_, j) => j !== i))}>✕</button></span>)}
            </div>
          )}
          {err && <div className="error small" role="alert">{err}</div>}
          <div className="row end">
            <button title="Datei anhängen" onClick={() => fileInput.current?.click()} disabled={!!editing}>📎</button>
            <input ref={fileInput} type="file" multiple hidden onChange={(x) => { setFiles([...files, ...Array.from(x.target.files ?? [])]); x.target.value = ''; }} />
            <button title="Codeblock" className={codeMode ? 'on' : ''} onClick={() => setCodeMode(!codeMode)} disabled={!!editing}>{'</>'}</button>
            {conv.kind === 'dm' && <button title="Einmal-Nachricht (nach dem Lesen gelöscht)" className={once ? 'on' : ''} onClick={() => setOnce(!once)} disabled={!!editing}>🔒</button>}
            {codeMode && <input className="lang" placeholder="Sprache" value={lang} onChange={(x) => setLang(x.target.value)} />}
            <textarea
              value={text} rows={codeMode ? 6 : 2} className={codeMode ? 'mono' : ''}
              placeholder={codeMode ? 'Code …' : 'Nachricht schreiben …'}
              onChange={(x) => setText(x.target.value)}
              onKeyDown={(x) => { if (x.key === 'Enter' && !x.shiftKey && !codeMode) { x.preventDefault(); if (!busy) void send(); } }}
            />
            <button className="primary" disabled={busy || (!text.trim() && files.length === 0)} onClick={send}>{busy ? '…' : 'Senden'}</button>
          </div>
        </div>
      ) : (
        <div className="banner">Du bist in dieser Unterhaltung nicht mehr Mitglied.</div>
      )}
      {filesOpen && <FilesDialog convId={conv.id} onClose={() => setFilesOpen(false)} />}
      {info && <ConvInfo conv={conv} onClose={() => setInfo(false)} onGone={onClosed} />}
    </div>
  );
}

function ChatHeader({ conv, onBack, onInfo, onFiles }: { conv: Conversation; onBack: () => void; onInfo: () => void; onFiles: () => void }) {
  const e = useEngine();
  const sec = convSecurity(e, conv);
  return (
    <header className="chat-header">
      <button className="back" onClick={onBack} aria-label="Zurück">←</button>
      <Avatar name={conv.title} size={40} />
      <div className="grow"><strong>{conv.title}</strong>
        <div className="muted small">{conv.kind === 'group' ? `${memberAddresses(conv).length} Mitglieder` : 'Ende-zu-Ende-verschlüsselt'}{conv.disappearSeconds ? ` · ⏱ ${fmtDur(conv.disappearSeconds)}` : ''}</div>
      </div>
      <button className={`sec-chip ${sec.level}`} onClick={onInfo} title="Sicherheitsnummer vergleichen">🔒 {sec.label}</button>
      <button onClick={onFiles} title="Dateien in diesem Chat" aria-label="Dateien">📁</button>
      <button onClick={onInfo} aria-label="Details">ⓘ</button>
    </header>
  );
}

function fmtDur(s: number): string {
  if (s < 3600) return `${Math.round(s / 60)} Min`;
  if (s < 86400) return `${Math.round(s / 3600)} Std`;
  return `${Math.round(s / 86400)} Tage`;
}

function ConvInfo({ conv, onClose, onGone }: { conv: Conversation; onClose: () => void; onGone: () => void }) {
  const e = useEngine();
  const s = e.state!;
  const [add, setAdd] = useState('');
  const [err, setErr] = useState('');
  const others = memberAddresses(conv).filter((a) => a !== s.me.address).map((address) => ({ address }));
  const dmContacts = Object.values(s.conversations).filter((c) => c.kind === 'dm' && c.status === 'active')
    .map((c) => c.members.find((m) => m.address !== s.me.address)?.address).filter((a): a is string => !!a && !conv.members.some((m) => m.address === a));
  const run = async (f: () => Promise<void> | void) => { setErr(''); try { await f(); } catch (x) { setErr((x as Error).message); } };
  return (
    <Dialog title={conv.title} onClose={onClose} wide>
      <h3>Mitglieder</h3>
      <ul className="members">
        {others.map((m) => {
          const c = s.contacts[m.address];
          return (
            <li key={m.address}>
              <div className="grow">
                <div>{m.address} {c?.verified ? <span className="ok">✔ verifiziert</span> : <span className="muted small">nicht verifiziert</span>}</div>
                <div className="mono small">{e.safetyNumber(m.address)}</div>
              </div>
              <div className="row">
                <button onClick={() => e.verifyContact(m.address, !c?.verified)}>{c?.verified ? 'Verifizierung entfernen' : 'Als verifiziert markieren'}</button>
                <button onClick={() => run(() => e.blockUser(m.address))}>Blockieren</button>
                {conv.kind === 'group' && <button onClick={() => run(() => e.removeMember(conv.id, m.address))}>Entfernen</button>}
              </div>
            </li>
          );
        })}
      </ul>
      <p className="muted small">Vergleiche die Sicherheitsnummer über einen anderen Kanal (persönlich, Telefon), um Manipulation durch Server auszuschließen.</p>
      {conv.kind === 'group' && dmContacts.length > 0 && (
        <div className="row">
          <select value={add} onChange={(x) => setAdd(x.target.value)}>
            <option value="">Mitglied hinzufügen …</option>
            {dmContacts.map((a) => <option key={a}>{a}</option>)}
          </select>
          <button disabled={!add} onClick={() => run(async () => { await e.addMember(conv.id, add); setAdd(''); })}>Hinzufügen</button>
        </div>
      )}
      <h3>Verschwindende Nachrichten</h3>
      <select value={conv.disappearSeconds} onChange={(x) => run(() => e.setDisappear(conv.id, Number(x.target.value)))}>
        <option value={0}>Aus</option><option value={3600}>1 Stunde</option><option value={86400}>1 Tag</option><option value={604800}>1 Woche</option>
      </select>
      <h3>Sicherheit</h3>
      <div className="row">
        <button onClick={() => run(() => e.rotateKeys(conv.id))}>Schlüssel erneuern</button>
        {conv.status === 'active' && <button onClick={() => { e.leaveConversation(conv.id); onClose(); }}>Verlassen</button>}
        <button className="danger" onClick={() => { if (confirm('Unterhaltung samt Verlauf lokal löschen?')) { e.deleteConversation(conv.id); onGone(); } }}>Löschen</button>
      </div>
      {err && <p className="error">{err}</p>}
    </Dialog>
  );
}
