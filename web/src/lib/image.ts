/** Bild auf ein kleines quadratisches JPEG (data-URL, ≤ ~14 KB) verkleinern: Profil-, Gruppen- und Kanalbilder. */
export async function toAvatar(file: File, size = 128): Promise<string> {
  if (!file.type.startsWith('image/')) throw new Error('Bitte ein Bild wählen.');
  const bmp = await createImageBitmap(file);
  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = size;
  const ctx = canvas.getContext('2d');
  if (!ctx) throw new Error('Bild konnte nicht verarbeitet werden.');
  const side = Math.min(bmp.width, bmp.height);
  ctx.fillStyle = '#fff';
  ctx.fillRect(0, 0, size, size);
  ctx.drawImage(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side, 0, 0, size, size);
  bmp.close();
  for (const q of [0.85, 0.7, 0.55, 0.4]) {
    const url = canvas.toDataURL('image/jpeg', q);
    if (url.length <= 14 * 1024) return url;
  }
  throw new Error('Bild ist zu detailreich.');
}
