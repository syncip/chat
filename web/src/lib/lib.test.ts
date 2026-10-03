import { describe, expect, it } from 'vitest';
import { b64, baseUrl, formatBytes, hex, solvePow, splitAddress, sha256, enc, unb64, unhex } from './util';
import { decodeCard, encodeCard } from './engine';

describe('util', () => {
  it('base64/hex roundtrip', () => {
    const b = new Uint8Array([0, 1, 2, 250, 255]);
    expect(unb64(b64(b))).toEqual(b);
    expect(unhex(hex(b))).toEqual(b);
  });
  it('splitAddress validiert', () => {
    expect(splitAddress('alice@chat.example.org')).toEqual({ name: 'alice', domain: 'chat.example.org' });
    expect(splitAddress('Alice@x.y')?.name).toBe('alice');
    expect(splitAddress('a@b')).toBeNull(); // Name zu kurz
    expect(splitAddress('alice@evil.com/../x')).toBeNull();
    expect(splitAddress('alice')).toBeNull();
  });
  it('baseUrl: https außer lokal', () => {
    expect(baseUrl('chat.example.org')).toBe('https://chat.example.org');
    expect(baseUrl('localhost:8080')).toBe('http://localhost:8080');
  });
  it('formatBytes', () => {
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(100 * 1024 * 1024)).toBe('100 MB');
  });
  it('Proof-of-Work erfüllt die geforderten Bits', async () => {
    const nonce = await solvePow('alice', 1700000000, 10);
    const h = await sha256(enc.encode(`alice:1700000000:${nonce}`));
    expect(h[0]).toBe(0);
    expect(h[1] >> 6).toBe(0);
  });
});

describe('Kontaktkarte', () => {
  const card = { address: 'bob@b.example', cap: { domain: 'b.example', mailbox_id: 'mb', send_token: 'tok', key: 'a2V5' } };
  it('encode/decode, auch aus vollständigem Link', () => {
    const e = encodeCard(card);
    expect(decodeCard(e).address).toBe('bob@b.example');
    const d = decodeCard(`https://x.example/#/add/${e}`);
    expect(d.cap).toMatchObject({ mailbox_id: 'mb', send_token: 'tok', intro: true });
  });
  it('lehnt Müll ab', () => {
    expect(() => decodeCard('nope!!')).toThrow();
    expect(() => decodeCard(encodeCard({ ...card, address: 'kein-at' }))).toThrow();
  });
});
