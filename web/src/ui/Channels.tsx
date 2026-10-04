import { useEffect, useRef, useState } from 'react';
import type { ChannelMember, ChannelPolicy, ChannelState, ChPost } from '../lib/types';
import { NeedsCaptcha, type ChannelInfo } from '../lib/channels';
import { copyText } from '../lib/util';
import { useEngine } from './hooks';
import { Dialog } from './Dialog';
import { Parts } from './Message';
import { Avatar } from './Avatar';

const DEFAULT_POLICY: ChannelPolicy = { join_mode: 'open', pow_bits: 16, probation_seconds: 0, members_can_write: false, slow_mode_seconds: 0 };

const ROLE: Record<string, string> = { owner: 'Besitzer', mod: 'Moderator', write: 'Schreiben', member: 'Mitglied', read: 'Nur lesen' };

function fmtDur(s: number): string {
  if (s <= 0) return 'keine';
  if (s < 3600) return `${Math.round(s / 60)} Min`;
  if (s < 86400) return `${Math.round(s / 3600)} Std`;
  return `${Math.round(s / 86400)} Tage`;
}

const UNITS = [['Minuten', 60], ['Stunden', 3600], ['Tage', 86400]] as const;

/** Dauer-Eingabe (Zahl + Einheit) → Sekunden. */
function Duration({ label, value, onChange }: { label: string; value: number; onChange: (s: number) => void }) {
  const unit = value % 86400 === 0 && value > 0 ? 2 : value % 3600 === 0 && value > 0 ? 1 : 0;
  const [u, setU] = useState<number>(unit);
  return (
    <label>{label}
      <div className="row">
        <input type="number" min={0} value={Math.round(value / UNITS[u][1])} onChange={(x) => onChange(Math.max(0, Number(x.target.value)) * UNITS[u][1])} />
        <select value={u} onChange={(x) => { const nu = Number(x.target.value); onChange(Math.round(value / UNITS[u][1]) * UNITS[nu][1]); setU(nu); }}>
          {UNITS.map(([n], i) => <option key={n} value={i}>{n}</option>)}
        </select>
      </div>
    </label>
  );
}

function PolicyForm({ p, onChange }: { p: ChannelPolicy; onChange: (p: ChannelPolicy) => void }) {
  return (
    <>
      <label>Beitritt
        <select value={p.join_mode} onChange={(x) => onChange({ ...p, join_mode: x.target.value as ChannelPolicy['join_mode'] })}>
          <option value="open">Direkter Zugriff (jeder mit dem Link)</option>
          <option value="approval">Freigabe durch Moderation nötig</option>
          <option value="pow">Proof-of-Work lösen</option>
          <option value="captcha">Captcha lösen</option>
        </select>
      </label>
      {p.join_mode === 'pow' && (
        <label>Schwierigkeit (Bits, 8–24)
          <input type="number" min={8} max={24} value={p.pow_bits} onChange={(x) => onChange({ ...p, pow_bits: Math.min(24, Math.max(8, Number(x.target.value))) })} />
        </label>
      )}
      <label className="check">
        <input type="checkbox" checked={p.members_can_write} onChange={(x) => onChange({ ...p, members_can_write: x.target.checked })} /> Alle Mitglieder dürfen schreiben (sonst nur lesen; Schreibrechte einzeln vergeben)
      </label>
      <Duration label="Neue Mitglieder dürfen erst schreiben nach" value={p.probation_seconds} onChange={(s) => onChange({ ...p, probation_seconds: s })} />
      <Duration label="Mindestabstand zwischen Beiträgen (Slow-Mode)" value={p.slow_mode_seconds} onChange={(s) => onChange({ ...p, slow_mode_seconds: s })} />
    </>
  );
}

export function CreateChannel({ onClose, onCreated }: { onClose: () => void; onCreated: (id: string) => void }) {
  const e = useEngine();
  const [title, setTitle] = useState('');
  const [p, setP] = useState<ChannelPolicy>(DEFAULT_POLICY);
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  return (
    <Dialog title="Neuer öffentlicher Kanal" onClose={onClose}>
      <p className="muted small">Jeder mit dem Link kann beitreten. Der Kanalschlüssel steht nur im Link (hinter dem #) und wird nie an den Server gesendet. Der Server sieht keine Inhalte, erzwingt aber Rechte und Sperren.</p>
      <label>Name<input value={title} onChange={(x) => setTitle(x.target.value)} maxLength={80} /></label>
      <PolicyForm p={p} onChange={setP} />
      {err && <p className="error">{err}</p>}
      <button className="primary" disabled={busy || !title.trim()} onClick={async () => {
        setBusy(true);
        setErr('');
        try { onCreated(await e.channels.create(title, p)); } catch (x) { setErr((x as Error).message); } finally { setBusy(false); }
      }}>Kanal erstellen</button>
    </Dialog>
  );
}

export function JoinChannel({ initial, onClose, onJoined }: { initial?: string; onClose: () => void; onJoined: (id: string) => void }) {
  const e = useEngine();
  const [link, setLink] = useState(initial ?? '');
  const [info, setInfo] = useState<(ChannelInfo & { link: { s: string } }) | null>(null);
  const [captcha, setCaptcha] = useState<{ token: string; image: string } | null>(null);
  const [answer, setAnswer] = useState('');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);

  async function load(text: string) {
    setErr('');
    setInfo(null);
    try { setInfo(await e.channels.preview(text)); } catch (x) { setErr((x as Error).message); }
  }
  useEffect(() => { if (initial) void load(initial); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, []);

  async function join() {
    setBusy(true);
    setErr('');
    try {
      onJoined(await e.channels.join(link, captcha ? { token: captcha.token, answer } : undefined));
    } catch (x) {
      if (x instanceof NeedsCaptcha) { setCaptcha({ token: x.token, image: x.image }); setAnswer(''); } else {
        setErr((x as Error).message);
        if (captcha) { setCaptcha(null); }
      }
    } finally { setBusy(false); }
  }

  const modeText: Record<string, string> = {
    open: 'Direkter Zugriff.', approval: 'Die Moderation muss dich freigeben.',
    pow: 'Dein Gerät löst beim Beitritt eine kleine Rechenaufgabe (kann einige Sekunden dauern).', captcha: 'Du musst ein Captcha lösen.',
  };
  return (
    <Dialog title="Kanal beitreten" onClose={onClose}>
      {!initial && (
        <>
          <textarea rows={3} value={link} onChange={(x) => setLink(x.target.value)} placeholder="https://…/#/join/…" />
          <button disabled={!link.trim()} onClick={() => load(link)}>Prüfen</button>
        </>
      )}
      {info && (
        <div className="card pad">
          <p><strong>{info.title}</strong> <span className="muted small">· {info.members} Mitglieder · Server {info.link.s}</span></p>
          <p className="muted small">{modeText[info.policy.join_mode]}{info.policy.probation_seconds > 0 ? ` Neue Mitglieder dürfen erst nach ${fmtDur(info.policy.probation_seconds)} schreiben.` : ''}</p>
          <p className="muted small">Mit dem Beitritt sieht der Kanal deine Adresse ({e.state!.me.address}).</p>
          {captcha && (
            <div>
              <img alt="Captcha" src={`data:image/png;base64,${captcha.image}`} />
              <input autoFocus inputMode="numeric" placeholder="Zahl eingeben" value={answer} onChange={(x) => setAnswer(x.target.value)} />
            </div>
          )}
          <button className="primary" disabled={busy || (!!captcha && !answer.trim())} onClick={join}>{busy ? 'Bitte warten …' : captcha ? 'Absenden' : 'Beitreten'}</button>
        </div>
      )}
      {err && <p className="error">{err}</p>}
    </Dialog>
  );
}

export function ChannelView({ ch, onBack, onGone }: { ch: ChannelState; onBack: () => void; onGone: () => void }) {
  const e = useEngine();
  const [text, setText] = useState('');
  const [code, setCode] = useState(false);
  const [lang, setLang] = useState('');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  const [info, setInfo] = useState(false);
  const bottom = useRef<HTMLDivElement>(null);
  const isMod = ch.me.status === 'active' && (ch.me.role === 'owner' || ch.me.role === 'mod');

  useEffect(() => { bottom.current?.scrollIntoView({ block: 'end' }); e.channels.markRead(ch.id); }, [ch.posts.length, ch.id, e]);

  async function send() {
    setBusy(true);
    setErr('');
    try {
      await e.channels.post(ch.id, [code ? { type: 'code', lang, body: text } : { type: 'text', body: text }]);
      setText('');
      setCode(false);
    } catch (x) { setErr((x as Error).message); } finally { setBusy(false); }
  }

  let blocker = '';
  if (ch.me.status === 'banned') blocker = 'Du wurdest aus diesem Kanal gesperrt.';
  else if (ch.me.status === 'pending') blocker = 'Dein Beitritt muss noch von der Moderation freigegeben werden.';
  else if (!ch.me.can_write) {
    if (ch.me.role === 'read') blocker = 'In diesem Kanal darfst du nur lesen.';
    else if (ch.me.muted_until * 1000 > Date.now()) blocker = `Du bist stummgeschaltet bis ${new Date(ch.me.muted_until * 1000).toLocaleString()}.`;
    else if (!ch.policy.members_can_write && ch.me.role !== 'write') blocker = 'In diesem Kanal darfst du nur lesen.';
    else blocker = `Neue Mitglieder dürfen erst nach ${fmtDur(ch.policy.probation_seconds)} schreiben.`;
  }

  return (
    <div className="chat">
      <header className="chat-header">
        <button className="back" onClick={onBack} aria-label="Zurück">←</button>
        <Avatar name={ch.title} size={40} channel />
        <div className="grow"><strong>📢 {ch.title}</strong>
          <div className="muted small">{ROLE[ch.me.role]} · {ch.server}</div>
        </div>
        <button onClick={() => setInfo(true)} aria-label="Details">ⓘ</button>
      </header>
      <div className="messages">
        {ch.posts.map((p) => <PostView key={p.id} ch={ch} p={p} isMod={isMod} onErr={setErr} />)}
        <div ref={bottom} />
      </div>
      {blocker ? <div className="banner">{blocker}</div> : (
        <div className="composer">
          {err && <div className="error small" role="alert">{err}</div>}
          <div className="row end">
            <button title="Codeblock" className={code ? 'on' : ''} onClick={() => setCode(!code)}>{'</>'}</button>
            {code && <input className="lang" placeholder="Sprache" value={lang} onChange={(x) => setLang(x.target.value)} />}
            <textarea value={text} rows={code ? 6 : 2} className={code ? 'mono' : ''} placeholder="Beitrag schreiben …" onChange={(x) => setText(x.target.value)}
              onKeyDown={(x) => { if (x.key === 'Enter' && !x.shiftKey && !code) { x.preventDefault(); if (!busy && text.trim()) void send(); } }} />
            <button className="primary" disabled={busy || !text.trim()} onClick={send}>{busy ? '…' : 'Senden'}</button>
          </div>
        </div>
      )}
      {blocker && err && <div className="error small" role="alert">{err}</div>}
      {info && <ChannelInfoDialog ch={ch} onClose={() => setInfo(false)} onGone={onGone} />}
    </div>
  );
}

function PostView({ ch, p, isMod, onErr }: { ch: ChannelState; p: ChPost; isMod: boolean; onErr: (m: string) => void }) {
  const e = useEngine();
  const mine = p.from === e.state!.me.address;
  const run = (f: () => Promise<void>) => f().catch((x) => onErr((x as Error).message));
  return (
    <div className={`msg ${mine ? 'mine' : 'theirs'}`}>
      <div className="meta small muted">
        {p.from} · {new Date(p.ts).toLocaleString()}
        {isMod && !p.deleted && (
          <span className="row">
            {' '}
            <button className="link" onClick={() => run(() => e.channels.mod(ch.id, { action: 'delete', post_id: p.id }))}>Löschen</button>
            {!mine && <button className="link" onClick={() => run(() => e.channels.mod(ch.id, { action: 'ban', target: p.ik }))}>Sperren</button>}
            {!mine && <button className="link" onClick={() => run(() => e.channels.mod(ch.id, { action: 'timeout', target: p.ik, seconds: 3600 }))}>1 Std stumm</button>}
          </span>
        )}
      </div>
      {p.deleted ? <em className="muted">Beitrag entfernt</em> : p.bad ? <em className="error">Beitrag konnte nicht verifiziert werden</em> : <Parts parts={p.parts} />}
    </div>
  );
}

function ChannelInfoDialog({ ch, onClose, onGone }: { ch: ChannelState; onClose: () => void; onGone: () => void }) {
  const e = useEngine();
  const isOwner = ch.me.role === 'owner';
  const isMod = isOwner || ch.me.role === 'mod';
  const [members, setMembers] = useState<ChannelMember[]>([]);
  const [p, setP] = useState<ChannelPolicy>(ch.policy);
  const [title, setTitle] = useState(ch.title);
  const [err, setErr] = useState('');
  const [copied, setCopied] = useState(false);
  const run = async (f: () => Promise<void>) => { setErr(''); try { await f(); if (isMod) setMembers(await e.channels.members(ch.id)); } catch (x) { setErr((x as Error).message); } };
  useEffect(() => { if (isMod) void e.channels.members(ch.id).then(setMembers).catch((x) => setErr((x as Error).message)); }, [ch.id, isMod, e]);
  const act = (m: ChannelMember, body: object) => run(() => e.channels.mod(ch.id, { target: m.ik, ...body } as never));
  return (
    <Dialog title={ch.title} onClose={onClose} wide>
      <h3>Einladungslink</h3>
      <p className="muted small">Der Link enthält den Kanalschlüssel. Wer ihn hat, kann (nach den Beitrittsregeln) lesen. Nach einer Sperre kennt die Person den Schlüssel weiterhin, der Server verweigert ihr aber Lesen und Schreiben.</p>
      <div className="row">
        <input readOnly value={e.channels.link(ch.id)} onFocus={(x) => x.target.select()} />
        <button onClick={async () => { await copyText(e.channels.link(ch.id)); setCopied(true); }}>{copied ? 'Kopiert' : 'Kopieren'}</button>
      </div>
      {isOwner && (
        <>
          <h3>Einstellungen (global)</h3>
          <label>Name<input value={title} onChange={(x) => setTitle(x.target.value)} maxLength={80} /></label>
          <PolicyForm p={p} onChange={setP} />
          <button onClick={() => run(() => e.channels.update(ch.id, { title, policy: p }))}>Speichern</button>
        </>
      )}
      {isMod && (
        <>
          <h3>Mitglieder</h3>
          <ul className="members">
            {members.map((m) => (
              <li key={m.ik}>
                <div className="grow">
                  <div>{m.address} <span className="muted small">· {ROLE[m.role]}{m.status !== 'active' ? ` · ${m.status === 'pending' ? 'wartet' : 'gesperrt'}` : ''}{m.muted_until * 1000 > Date.now() ? ' · stumm' : ''}</span></div>
                </div>
                {m.role !== 'owner' && (isOwner || m.role !== 'mod') && (
                  <div className="row">
                    {m.status === 'pending' && <><button onClick={() => act(m, { action: 'approve' })}>Freigeben</button><button onClick={() => act(m, { action: 'reject' })}>Ablehnen</button></>}
                    {m.status === 'active' && (
                      <>
                        <select value={m.role} onChange={(x) => act(m, { action: 'role', role: x.target.value })}>
                          <option value="member">Mitglied (global)</option><option value="write">Lesen + Schreiben</option><option value="read">Nur lesen</option>
                          {isOwner && <option value="mod">Moderator</option>}
                        </select>
                        <button onClick={() => act(m, { action: 'timeout', seconds: m.muted_until * 1000 > Date.now() ? 0 : 3600 })}>{m.muted_until * 1000 > Date.now() ? 'Stumm aufheben' : '1 Std stumm'}</button>
                        <button onClick={() => act(m, { action: 'kick' })}>Entfernen</button>
                        <button onClick={() => act(m, { action: 'ban' })}>Sperren</button>
                      </>
                    )}
                    {m.status === 'banned' && <button onClick={() => act(m, { action: 'unban' })}>Entsperren</button>}
                  </div>
                )}
              </li>
            ))}
          </ul>
        </>
      )}
      <h3>Protokoll</h3>
      <ul className="small">
        {ch.events.slice(-20).reverse().map((ev) => <li key={ev.seq}>{new Date(ev.ts).toLocaleString()} · {ev.actor} · {ev.kind}{ev.targetAddress ? ` → ${ev.targetAddress}` : ''}</li>)}
      </ul>
      <div className="row">
        {!isOwner && <button onClick={async () => { await e.channels.leave(ch.id); onGone(); }}>Kanal verlassen</button>}
        {isOwner && <button className="danger" onClick={() => { if (confirm('Kanal für alle löschen?')) void run(async () => { await e.channels.remove(ch.id); onGone(); }); }}>Kanal löschen</button>}
      </div>
      {err && <p className="error">{err}</p>}
    </Dialog>
  );
}
