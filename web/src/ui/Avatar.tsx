import { toAvatar } from '../lib/image';

/** Runder Avatar mit Initialen; die Farbe ergibt sich stabil aus dem Namen. */
export function Avatar({ name, size = 40, channel = false, src }: { name: string; size?: number; channel?: boolean; src?: string }) {
  if (src) return <img className={`avatar ${channel ? 'square' : ''}`} src={src} alt="" aria-hidden="true" style={{ width: size, height: size, objectFit: 'cover' }} />;
  let h = 0;
  for (const c of name) h = (h * 31 + c.charCodeAt(0)) >>> 0;
  const hue = h % 360;
  const initials = (name.split('@')[0].replace(/[^\p{L}\p{N} ]/gu, ' ').trim().split(/\s+/).map((w) => w[0]).join('').slice(0, 2) || '?').toUpperCase();
  return (
    <span
      className={`avatar ${channel ? 'square' : ''}`} aria-hidden="true"
      style={{ width: size, height: size, fontSize: size * 0.4, background: `linear-gradient(135deg, hsl(${hue} 62% 52%), hsl(${(hue + 40) % 360} 62% 42%))` }}
    >
      {initials}
    </span>
  );
}

/** Bild wählen/entfernen (verkleinert auf ein kleines Quadrat). */
export function AvatarPicker({ name, src, channel = false, label, onPick, onError }: { name: string; src?: string; channel?: boolean; label: string; onPick: (dataUrl: string | null) => void | Promise<void>; onError?: (m: string) => void }) {
  return (
    <div className="avatar-picker">
      <Avatar name={name} src={src} size={64} channel={channel} />
      <label className="button">
        {label}
        <input type="file" accept="image/*" hidden aria-label={label} onChange={async (ev) => {
          const f = ev.target.files?.[0];
          ev.target.value = '';
          if (!f) return;
          try { await onPick(await toAvatar(f)); } catch (x) { onError?.((x as Error).message); }
        }} />
      </label>
      {src && <button type="button" onClick={() => void onPick(null)}>Bild entfernen</button>}
    </div>
  );
}
