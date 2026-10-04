package server

import (
	"context"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"math/bits"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/syncip/chat/server/internal/store"
)

const maxSkew = 60 * time.Second

type ctxKey struct{}

var devRe = regexp.MustCompile(`^[0-9a-f]{16}$`)

// devOf liefert das (per Signatur geprüfte) Gerät der aktuellen Anfrage.
func devOf(r *http.Request) *store.Device {
	d, _ := r.Context().Value(ctxKey{}).(*store.Device)
	return d
}

// Authorization: Chat-Sig name=<n>,dev=<geräte-id>,ts=<unix>,nonce=<b64>,sig=<b64>   (Signatur mit dem Geräteschlüssel)
// Signiert wird: "CHAT-REQ-V1\n<domain>\n<METHOD>\n<RequestURI>\n<ts>\n<nonce>\n<hex sha256(body)>"
// Der Server-Domain-Bezug verhindert Replay gegen andere Server.
func canonicalRequest(domain, method, uri, ts, nonce, bodyHash string) []byte {
	return []byte("CHAT-REQ-V1\n" + domain + "\n" + method + "\n" + uri + "\n" + ts + "\n" + nonce + "\n" + bodyHash)
}

func parseAuth(h string) map[string]string {
	h, ok := strings.CutPrefix(h, "Chat-Sig ")
	if !ok {
		return nil
	}
	m := map[string]string{}
	for _, kv := range strings.Split(h, ",") {
		k, v, ok := strings.Cut(strings.TrimSpace(kv), "=")
		if !ok {
			return nil
		}
		m[k] = v
	}
	return m
}

func (s *Server) verifySig(domain, method, uri string, a map[string]string, bodyHash string) (*store.User, *store.Device, bool) {
	name, dev, ts, nonce, sig := a["name"], a["dev"], a["ts"], a["nonce"], a["sig"]
	if name == "" || !devRe.MatchString(dev) || ts == "" || len(nonce) < 8 || len(nonce) > 64 || sig == "" {
		return nil, nil, false
	}
	t, err := strconv.ParseInt(ts, 10, 64)
	if err != nil {
		return nil, nil, false
	}
	if d := time.Since(time.Unix(t, 0)); d > maxSkew || d < -maxSkew {
		return nil, nil, false
	}
	sigb, err := b64.DecodeString(sig)
	if err != nil || len(sigb) != ed25519.SignatureSize {
		return nil, nil, false
	}
	u, err := s.st.UserByName(name)
	if err != nil {
		return nil, nil, false
	}
	d, err := s.st.DeviceByID(u.ID, dev) // widerrufene Geräte existieren nicht mehr
	if err != nil || len(d.DPK) != ed25519.PublicKeySize {
		return nil, nil, false
	}
	if !ed25519.Verify(d.DPK, canonicalRequest(domain, method, uri, ts, nonce, bodyHash), sigb) {
		return nil, nil, false
	}
	// Nonce erst nach gültiger Signatur merken (kein Cache-Flooding durch Unbefugte).
	if s.nonces.seen(name + ":" + dev + ":" + nonce) {
		return nil, nil, false
	}
	return u, d, true
}

type userHandler func(w http.ResponseWriter, r *http.Request, u *store.User)

// auth: Body wird gelesen (≤ 2 MiB) und in die Signatur einbezogen.
func (s *Server) auth(h userHandler) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		a := parseAuth(r.Header.Get("Authorization"))
		if a == nil {
			writeErr(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 2<<20))
		if err != nil {
			writeErr(w, http.StatusRequestEntityTooLarge, "body too large")
			return
		}
		sum := sha256.Sum256(body)
		u, d, ok := s.verifySig(s.conf().Domain, r.Method, r.URL.RequestURI(), a, hex.EncodeToString(sum[:]))
		if !ok {
			writeErr(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		r.Body = io.NopCloser(strings.NewReader(string(body)))
		h(w, r.WithContext(context.WithValue(r.Context(), ctxKey{}, d)), u)
	}
}

// authStream: für große Uploads. Der Body ist nicht Teil der Signatur (X-Body-Hash: UNSIGNED);
// die Datei ist ohnehin Ende-zu-Ende authentifiziert verschlüsselt.
func (s *Server) authStream(h userHandler) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		a := parseAuth(r.Header.Get("Authorization"))
		if a == nil || r.Header.Get("X-Body-Hash") != "UNSIGNED" {
			writeErr(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		u, d, ok := s.verifySig(s.conf().Domain, r.Method, r.URL.RequestURI(), a, "UNSIGNED")
		if !ok {
			writeErr(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		h(w, r.WithContext(context.WithValue(r.Context(), ctxKey{}, d)), u)
	}
}

// ---- Proof-of-Work (offene Registrierung) ----

func (s *Server) powBits() int {
	if s.conf().Registration == "open" {
		return s.conf().RegistrationPoW
	}
	return 0
}

// powOK prüft sha256(name ":" ts ":" nonce) auf führende Null-Bits.
func powOK(name, ts, nonce string, bitsNeeded int) bool {
	if bitsNeeded <= 0 {
		return true
	}
	h := sha256.Sum256([]byte(name + ":" + ts + ":" + nonce))
	n := 0
	for _, b := range h {
		if b == 0 {
			n += 8
			continue
		}
		n += bits.LeadingZeros8(b)
		break
	}
	return n >= bitsNeeded
}
