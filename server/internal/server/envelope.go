package server

import (
	"crypto/rand"
	"encoding/binary"
	"errors"

	"golang.org/x/crypto/chacha20poly1305"
)

// Umschlag-Format des Krypto-Kerns (core/src/envelope.rs, padding.rs): nonce(24) ‖ XChaCha20-Poly1305(pad(inner)),
// inner = kind ‖ len(gid) ‖ gid ‖ len(dev) ‖ dev ‖ payload. Der Server braucht es nur, um Webhook-Beiträge für Kanäle
// zu verschlüsseln, deren Schlüssel der Kanalverwalter dem Webhook mitgegeben hat (und um diesen Schlüssel zu prüfen).

const kindChannel = 3

func padBlock(data []byte) []byte {
	total := len(data) + 4
	if total < 256 {
		total = 256
	}
	n := 1
	for n < total {
		n <<= 1
	}
	out := make([]byte, n)
	binary.BigEndian.PutUint32(out, uint32(len(data)))
	copy(out[4:], data)
	return out
}

func unpadBlock(b []byte) ([]byte, error) {
	if len(b) < 4 {
		return nil, errors.New("padding")
	}
	n := int(binary.BigEndian.Uint32(b))
	if 4+n > len(b) {
		return nil, errors.New("padding")
	}
	return b[4 : 4+n], nil
}

func sealEnvelope(key []byte, kind byte, gid, payload []byte) ([]byte, error) {
	aead, err := chacha20poly1305.NewX(key)
	if err != nil {
		return nil, err
	}
	inner := []byte{kind, byte(len(gid) >> 8), byte(len(gid))}
	inner = append(inner, gid...)
	inner = append(inner, 0) // keine Absender-Geräte-ID
	inner = append(inner, payload...)
	nonce := make([]byte, chacha20poly1305.NonceSizeX)
	_, _ = rand.Read(nonce)
	return append(nonce, aead.Seal(nil, nonce, padBlock(inner), nil)...), nil
}

// openEnvelope liefert (kind, gid, payload).
func openEnvelope(key, blob []byte) (byte, []byte, []byte, error) {
	aead, err := chacha20poly1305.NewX(key)
	if err != nil || len(blob) < chacha20poly1305.NonceSizeX+16 {
		return 0, nil, nil, errors.New("envelope")
	}
	pt, err := aead.Open(nil, blob[:chacha20poly1305.NonceSizeX], blob[chacha20poly1305.NonceSizeX:], nil)
	if err != nil {
		return 0, nil, nil, err
	}
	in, err := unpadBlock(pt)
	if err != nil || len(in) < 4 {
		return 0, nil, nil, errors.New("envelope")
	}
	n := int(in[1])<<8 | int(in[2])
	if 3+n+1 > len(in) {
		return 0, nil, nil, errors.New("envelope")
	}
	gid := in[3 : 3+n]
	dl := int(in[3+n])
	if 4+n+dl > len(in) {
		return 0, nil, nil, errors.New("envelope")
	}
	return in[0], gid, in[4+n+dl:], nil
}
