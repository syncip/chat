import qrcode from 'qrcode-generator';

/** Rendert Text als QR-Code (SVG, schwarz auf weiß, damit er auch im Dunkelmodus scanbar bleibt). */
export function QrCode({ text, label }: { text: string; label: string }) {
  const q = qrcode(0, 'M');
  q.addData(text);
  q.make();
  const svg = q.createSvgTag({ cellSize: 5, margin: 4, scalable: true });
  return <div className="qr" role="img" aria-label={label} dangerouslySetInnerHTML={{ __html: svg }} />;
}
