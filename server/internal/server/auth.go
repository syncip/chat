package server

import (
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"math/bits"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/syncip/chat/server/internal/store"
)

const maxSkew = 60 * time.Second

type ctxKey struct{}

// Authorization: Chat-Sig name=<n>,ts=<unix>,nonce=<b64>,sig=<b64>
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

func (s *Server) verifySig(domain, method, uri string, a map[string]string, bodyHash string) (*store.User, bool) {
	name, ts, nonce, sig := a["name"], a["ts"], a["nonce"], a["sig"]
	if name == "" || ts == "" || len(nonce) < 8 || len(nonce) > 64 || sig == "" {
		return nil, false
	}
	t, err := strconv.ParseInt(ts, 10, 64)
	if err != nil {
		return nil, false
	}
	if d := time.Since(time.Unix(t, 0)); d > maxSkew || d < -maxSkew {
		return nil, false
	}
	sigb, err := b64.DecodeString(sig)
	if err != nil || len(sigb) != ed25519.SignatureSize {
		return nil, false
	}
	u, err := s.st.UserByName(name)
	if err != nil || len(u.IK) != ed25519.PublicKeySize {
		return nil, false
	}
	if !ed25519.Verify(u.IK, canonicalRequest(domain, method, uri, ts, nonce, bodyHash), sigb) {
		return nil, false
	}
	// Nonce erst nach gültiger Signatur merken (kein Cache-Flooding durch Unbefugte).
	if s.nonces.seen(name + ":" + nonce) {
		return nil, false
	}
	return u, true
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
		u, ok := s.verifySig(s.cfg.Domain, r.Method, r.URL.RequestURI(), a, hex.EncodeToString(sum[:]))
		if !ok {
			writeErr(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		r.Body = io.NopCloser(strings.NewReader(string(body)))
		h(w, r, u)
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
		u, ok := s.verifySig(s.cfg.Domain, r.Method, r.URL.RequestURI(), a, "UNSIGNED")
		if !ok {
			writeErr(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		h(w, r, u)
	}
}

// ---- Proof-of-Work (offene Registrierung) ----

func (s *Server) powBits() int {
	if s.cfg.Registration == "open" {
		return s.cfg.RegistrationPoW
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
