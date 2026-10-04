import { securityReport, type Level } from '../lib/security';
import { useEngine } from './hooks';
import { Dialog } from './Dialog';

const ICON: Record<Level, string> = { ok: '✔', info: 'ℹ', warn: '⚠', bad: '✖' };

export function SecurityDialog({ onClose }: { onClose: () => void }) {
  const e = useEngine();
  const r = securityReport(e);
  const alerts = e.state!.alerts ?? [];
  const headline = r.level === 'bad' ? 'Handlungsbedarf' : r.level === 'warn' ? 'Es gibt Hinweise' : 'Alles in Ordnung';
  return (
    <Dialog title="Sicherheit" onClose={onClose} wide>
      <div className={`sec-head ${r.level}`}>
        <span className="sec-icon">🛡</span>
        <div><strong>{headline}</strong><div className="small">{r.level === 'ok' ? 'Keine Auffälligkeiten erkannt.' : 'Prüfe die markierten Punkte.'}</div></div>
      </div>
      {alerts.length > 0 && (
        <div className="sec-alerts">
          {alerts.map((a) => (
            <div key={a.id} className={`banner ${a.kind === 'key' ? 'bad' : 'warn'}`} role="alert">
              <span className="grow">{a.text}</span>
              <button className="link" onClick={() => e.dismissAlert(a.id)}>Gesehen</button>
            </div>
          ))}
        </div>
      )}
      <ul className="sec-list">
        {r.items.map((i, k) => (
          <li key={k} className={i.level}>
            <span className="sec-ico">{ICON[i.level]}</span>
            <div><div className="sec-title">{i.title}</div><div className="muted small">{i.detail}</div></div>
          </li>
        ))}
      </ul>
    </Dialog>
  );
}
