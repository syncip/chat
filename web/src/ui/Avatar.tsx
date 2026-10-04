/** Runder Avatar mit Initialen; die Farbe ergibt sich stabil aus dem Namen. */
export function Avatar({ name, size = 40, channel = false }: { name: string; size?: number; channel?: boolean }) {
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
