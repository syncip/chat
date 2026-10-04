// Package server implementiert die HTTP-API (siehe docs/PROTOCOL.md).
package server

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base32"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/syncip/chat/server/internal/config"
	"github.com/syncip/chat/server/internal/store"
)

const apiVersion = 1

var nameRe = regexp.MustCompile(`^[a-z0-9][a-z0-9._-]{1,31}$`)

type Server struct {
	cfgp     atomic.Pointer[config.Config]
	started  time.Time
	reqTotal atomic.Int64
	st       *store.Store
	log      *slog.Logger
	key      ed25519.PrivateKey
	hub      *hub
	chans    *chanHub
	nonces   *nonceCache
	limiter  *limiter
	fed      *federation
	webHash  string
	blobDir  string
	mux      *http.ServeMux
	stopOnce sync.Once
	stop     chan struct{}
}

func New(cfg *config.Config, st *store.Store, log *slog.Logger) (*Server, error) {
	s := &Server{
		st: st, log: log, started: time.Now(),
		hub:     newHub(),
		chans:   newChanHub(),
		nonces:  newNonceCache(5 * time.Minute),
		limiter: newLimiter(cfg.RatePerMinute),
		blobDir: filepath.Join(cfg.DataDir, "blobs"),
		stop:    make(chan struct{}),
	}
	s.cfgp.Store(cfg)
	if err := s.loadSettings(); err != nil {
		return nil, err
	}
	if err := os.MkdirAll(s.blobDir, 0o700); err != nil {
		return nil, err
	}
	if err := s.loadKey(); err != nil {
		return nil, err
	}
	s.fed = newFederation(s)
	if cfg.WebDir != "" {
		h, err := hashDir(cfg.WebDir)
		if err != nil {
			return nil, err
		}
		s.webHash = h
	}
	s.routes()
	return s, nil
}

func (s *Server) Handler() http.Handler { return s }

// conf liefert die aktuelle Konfiguration (kann vom Administrator zur Laufzeit ersetzt werden).
func (s *Server) conf() *config.Config { return s.cfgp.Load() }

// Close beendet Hintergrundjobs.
func (s *Server) Close() { s.stopOnce.Do(func() { close(s.stop) }) }

func (s *Server) loadKey() error {
	if v, err := s.st.Meta("server_key"); err == nil && len(v) == ed25519.PrivateKeySize {
		s.key = ed25519.PrivateKey(v)
		return nil
	} else if err != nil && !errors.Is(err, store.ErrNotFound) {
		return err
	}
	_, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return err
	}
	s.key = priv
	return s.st.SetMeta("server_key", priv)
}

func (s *Server) routes() {
	m := http.NewServeMux()
	// öffentlich
	m.HandleFunc("GET /.well-known/chat-server", s.wellKnown)
	m.HandleFunc("GET /v1/server-info", s.serverInfo)
	m.HandleFunc("POST /v1/register", s.register)
	m.HandleFunc("GET /v1/users/{name}", s.userInfo)
	m.HandleFunc("GET /v1/users/{name}/keypackages", s.getKeyPackages)
	m.HandleFunc("POST /v1/devices", s.addDevice)
	m.HandleFunc("PUT /v1/mailboxes/{id}/messages", s.putMessage)
	m.HandleFunc("GET /v1/blobs/{id}", s.getBlob)
	m.HandleFunc("POST /v1/federation/deliver", s.fedDeliver)
	// Admin
	m.HandleFunc("POST /v1/admin/invites", s.adminInvite)
	// authentifiziert (signierte Requests)
	m.HandleFunc("POST /v1/invites", s.auth(s.userInvite))
	m.HandleFunc("PUT /v1/keypackages", s.auth(s.putKeyPackages))
	m.HandleFunc("GET /v1/keypackages/count", s.auth(s.countKeyPackages))
	m.HandleFunc("GET /v1/resolve/{address}/keypackages", s.auth(s.resolveKeyPackages))
	m.HandleFunc("GET /v1/devices", s.auth(s.listDevices))
	m.HandleFunc("DELETE /v1/devices/{id}", s.auth(s.revokeDevice))
	m.HandleFunc("GET /v1/mailboxes", s.auth(s.listMailboxes))
	m.HandleFunc("POST /v1/mailboxes", s.auth(s.createMailbox))
	m.HandleFunc("DELETE /v1/mailboxes/{id}", s.auth(s.deleteMailbox))
	m.HandleFunc("GET /v1/messages", s.auth(s.getMessages))
	m.HandleFunc("DELETE /v1/messages", s.auth(s.ackMessages))
	m.HandleFunc("POST /v1/relay", s.auth(s.relay))
	m.HandleFunc("PUT /v1/filters", s.auth(s.putFilters))
	m.HandleFunc("POST /v1/blobs", s.authStream(s.postBlob))
	m.HandleFunc("DELETE /v1/blobs/{id}", s.auth(s.deleteBlob))
	m.HandleFunc("GET /v1/quota", s.auth(s.quota))
	m.HandleFunc("GET /v1/stream", s.stream)
	m.HandleFunc("GET /v1/me", s.auth(s.me))
	m.HandleFunc("GET /v1/admin/stats", s.adminOnly(s.adminStats))
	m.HandleFunc("GET /v1/admin/settings", s.adminOnly(s.adminGetSettings))
	m.HandleFunc("PUT /v1/admin/settings", s.adminOnly(s.adminPutSettings))
	m.HandleFunc("GET /v1/admin/users", s.adminOnly(s.adminUsers))
	m.HandleFunc("PUT /v1/admin/users/{name}/admin", s.adminOnly(s.adminSetAdmin))
	m.HandleFunc("GET /v1/sync", s.auth(s.getSync))
	m.HandleFunc("PUT /v1/sync", s.auth(s.putSync))
	// Kanäle
	m.HandleFunc("POST /v1/channels", s.auth(s.createChannel))
	m.HandleFunc("GET /v1/channels/{id}", s.channelInfo)
	m.HandleFunc("GET /v1/channels/{id}/captcha", s.channelCaptcha)
	m.HandleFunc("GET /v1/channels/{id}/stream", s.channelStream)
	m.HandleFunc("GET /v1/channels/{id}/public/log", s.publicChannelLog)
	m.HandleFunc("GET /v1/channels/{id}/public/events", s.publicChannelEvents)
	m.HandleFunc("POST /v1/channels/{id}/hooks", s.chanAuth(s.channelHookCreate))
	m.HandleFunc("GET /v1/channels/{id}/hooks", s.chanAuth(s.channelHookList))
	m.HandleFunc("DELETE /v1/channels/{id}/hooks/{hid}", s.chanAuth(s.channelHookDelete))
	for _, pfx := range []string{"/h/{token}", "/v1/hook/{token}"} {
		for _, sfx := range []string{"", "/publish", "/send", "/trigger"} {
			m.HandleFunc("POST "+pfx+sfx, s.hookPublish)
			m.HandleFunc("PUT "+pfx+sfx, s.hookPublish)
			if sfx != "" {
				m.HandleFunc("GET "+pfx+sfx, s.hookPublish)
			}
		}
	}
	m.HandleFunc("POST /v1/channels/{id}/join", s.chanAuth(s.channelJoin))
	m.HandleFunc("GET /v1/channels/{id}/log", s.chanAuth(s.channelLog))
	m.HandleFunc("POST /v1/channels/{id}/posts", s.chanAuth(s.channelPost))
	m.HandleFunc("POST /v1/channels/{id}/mod", s.chanAuth(s.channelMod))
	m.HandleFunc("GET /v1/channels/{id}/members", s.chanAuth(s.channelMembers))
	m.HandleFunc("PUT /v1/channels/{id}/settings", s.chanAuth(s.channelSettings))
	m.HandleFunc("POST /v1/channels/{id}/leave", s.chanAuth(s.channelLeave))
	m.HandleFunc("DELETE /v1/channels/{id}", s.chanAuth(s.channelDelete))
	if s.conf().WebDir != "" {
		m.Handle("/", s.webHandler())
	}
	s.mux = m
}

// ServeHTTP: gemeinsame Middleware (Sicherheits-Header, CORS, Rate-Limit). Keine Logs mit IPs.
func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	h := w.Header()
	h.Set("X-Content-Type-Options", "nosniff")
	h.Set("Referrer-Policy", "no-referrer")
	h.Set("Cache-Control", "no-store")
	if strings.HasPrefix(r.URL.Path, "/v1/") || strings.HasPrefix(r.URL.Path, "/h/") || strings.HasPrefix(r.URL.Path, "/.well-known/") {
		h.Set("Access-Control-Allow-Origin", "*")
		h.Set("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Send-Token, X-Body-Hash, X-Admin-Key, Title, Priority, Tags, Click, X-Title, X-Priority, X-Tags, X-Click, Last-Event-ID")
		h.Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		h.Set("Access-Control-Max-Age", "600")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		if !s.limiter.allow(s.clientIP(r)) {
			writeErr(w, http.StatusTooManyRequests, "rate limited")
			return
		}
	}
	s.reqTotal.Add(1)
	defer func() {
		if rec := recover(); rec != nil && rec != http.ErrAbortHandler {
			s.log.Error("panic", "path", r.URL.Path, "err", rec)
			writeErr(w, http.StatusInternalServerError, "internal error")
		}
	}()
	s.mux.ServeHTTP(w, r)
}

func (s *Server) clientIP(r *http.Request) string {
	if hdr := s.conf().TrustProxyHdr; hdr != "" {
		if v := r.Header.Get(hdr); v != "" {
			return strings.TrimSpace(strings.Split(v, ",")[0])
		}
	}
	host := r.RemoteAddr
	if i := strings.LastIndex(host, ":"); i > 0 {
		host = host[:i]
	}
	return host
}

// ---- Hilfsfunktionen ----

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, code int, msg string) {
	writeJSON(w, code, map[string]string{"error": msg})
}

func readJSON(w http.ResponseWriter, r *http.Request, max int64, v any) bool {
	b, err := io.ReadAll(http.MaxBytesReader(w, r.Body, max))
	if err != nil {
		writeErr(w, http.StatusRequestEntityTooLarge, "body too large")
		return false
	}
	return decodeJSON(w, b, v)
}

func decodeJSON(w http.ResponseWriter, b []byte, v any) bool {
	dec := json.NewDecoder(strings.NewReader(string(b)))
	dec.DisallowUnknownFields()
	if err := dec.Decode(v); err != nil {
		writeErr(w, http.StatusBadRequest, "invalid json")
		return false
	}
	return true
}

func randID(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return strings.ToLower(base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString(b))
}

func hashToken(t string) []byte {
	h := sha256.Sum256([]byte(t))
	return h[:]
}

func tokenEq(hash []byte, token string) bool {
	return subtle.ConstantTimeCompare(hash, hashToken(token)) == 1
}

var b64 = base64.StdEncoding

func jsonUnmarshal(b []byte, v any) bool { return json.Unmarshal(b, v) == nil }

func (s *Server) isLocal(domain string) bool { return strings.EqualFold(domain, s.conf().Domain) }

// ---- Öffentliche Basis-Endpunkte ----

func (s *Server) wellKnown(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, map[string]any{
		"domain":     s.conf().Domain,
		"server_key": b64.EncodeToString(s.key.Public().(ed25519.PublicKey)),
		"version":    apiVersion,
	})
}

func (s *Server) serverInfo(w http.ResponseWriter, r *http.Request) {
	c := s.conf()
	writeJSON(w, 200, map[string]any{
		"domain":       c.Domain,
		"version":      apiVersion,
		"registration": c.Registration,
		"federation":   c.Federation,
		"server_key":   b64.EncodeToString(s.key.Public().(ed25519.PublicKey)),
		"client_hash":  s.webHash,
		"pow_bits":     s.powBits(),
		"limits": map[string]any{
			"max_file_size":           c.MaxFileSize,
			"max_message_attachments": c.MaxAttachments,
			"max_message_total_size":  c.MaxMessageTotal,
			"max_message_text":        c.MaxMessageText,
			"max_envelope_size":       c.MaxEnvelopeSize,
			"user_quota":              c.UserQuota,
			"blob_retention_days":     int(c.BlobRetention.Hours() / 24),
			"message_retention_days":  int(c.MessageRetention.Hours() / 24),
		},
	})
}

func (s *Server) userInfo(w http.ResponseWriter, r *http.Request) {
	u, err := s.st.UserByName(r.PathValue("name"))
	if err != nil {
		writeErr(w, 404, "not found")
		return
	}
	writeJSON(w, 200, map[string]any{"name": u.Name, "ik": b64.EncodeToString(u.IK)})
}

// ---- Hintergrundjobs ----

func (s *Server) RunJanitor(ctx context.Context) {
	t := time.NewTicker(10 * time.Minute)
	defer t.Stop()
	for {
		s.sweep()
		select {
		case <-ctx.Done():
			return
		case <-s.stop:
			return
		case <-t.C:
		}
	}
}

func (s *Server) sweep() {
	ids, err := s.st.ExpiredBlobs()
	if err != nil {
		s.log.Error("sweep blobs", "err", err)
	}
	for _, id := range ids {
		_ = os.Remove(s.blobPath(id))
	}
	if err := s.st.PurgeMessages(s.conf().MessageRetention); err != nil {
		s.log.Error("purge", "err", err)
	}
	if err := s.st.PurgeChannelLog(s.conf().ChannelRetention); err != nil {
		s.log.Error("purge channels", "err", err)
	}
	s.nonces.sweep()
	s.limiter.sweep()
}
