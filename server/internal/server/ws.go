package server

import (
	"context"
	"encoding/json"
	"net/http"
	"strconv"
	"sync"
	"time"

	"github.com/coder/websocket"
	"github.com/syncip/chat/server/internal/store"
)

type event struct {
	Type      string // "message" | "devices"
	Seq       int64
	MailboxID string
	Data      []byte
}

// hub verteilt Ereignisse an die WebSocket-Verbindungen je **Gerät**.
type hub struct {
	mu   sync.Mutex
	subs map[string]map[chan event]struct{}
}

func newHub() *hub { return &hub{subs: map[string]map[chan event]struct{}{}} }

func hubKey(uid int64, dev string) string { return strconv.FormatInt(uid, 10) + ":" + dev }

func (h *hub) count() (n int) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, m := range h.subs {
		n += len(m)
	}
	return
}

func (h *hub) subscribe(uid int64, dev string) chan event {
	ch := make(chan event, 64)
	k := hubKey(uid, dev)
	h.mu.Lock()
	if h.subs[k] == nil {
		h.subs[k] = map[chan event]struct{}{}
	}
	h.subs[k][ch] = struct{}{}
	h.mu.Unlock()
	return ch
}

func (h *hub) unsubscribe(uid int64, dev string, ch chan event) {
	k := hubKey(uid, dev)
	h.mu.Lock()
	if _, ok := h.subs[k][ch]; ok {
		delete(h.subs[k], ch)
		if len(h.subs[k]) == 0 {
			delete(h.subs, k)
		}
	}
	h.mu.Unlock()
}

// kick trennt alle Verbindungen eines (widerrufenen) Geräts.
func (h *hub) kick(uid int64, dev string) {
	k := hubKey(uid, dev)
	h.mu.Lock()
	defer h.mu.Unlock()
	for ch := range h.subs[k] {
		close(ch)
	}
	delete(h.subs, k)
}

// publish blockiert nie: überläuft ein Abonnent, holt er sich die Nachrichten per REST nach.
func (h *hub) publish(uid int64, dev string, e event) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for ch := range h.subs[hubKey(uid, dev)] {
		select {
		case ch <- e:
		default:
		}
	}
}

type wsAuth struct {
	Name  string `json:"name"`
	Dev   string `json:"dev"`
	Ts    string `json:"ts"`
	Nonce string `json:"nonce"`
	Sig   string `json:"sig"`
}

// stream: WebSocket. Browser können keine Header setzen, deshalb authentifiziert die erste Nachricht
// (signiert über "GET /v1/stream", Body-Hash "WS").
func (s *Server) stream(w http.ResponseWriter, r *http.Request) {
	c, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true}) // Auth per Signatur, kein Cookie
	if err != nil {
		return
	}
	defer c.CloseNow()
	c.SetReadLimit(4 << 10)
	ctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
	_, msg, err := c.Read(ctx)
	cancel()
	if err != nil {
		return
	}
	var a wsAuth
	var u *store.User
	var dev *store.Device
	var ok bool
	if json.Unmarshal(msg, &a) == nil {
		u, dev, ok = s.verifySig(s.conf().Domain, "GET", "/v1/stream", map[string]string{
			"name": a.Name, "dev": a.Dev, "ts": a.Ts, "nonce": a.Nonce, "sig": a.Sig}, "WS")
	}
	if !ok {
		_ = c.Close(websocket.StatusPolicyViolation, "unauthorized")
		return
	}
	if u.Banned(time.Now().Unix()) {
		_ = c.Close(websocket.StatusPolicyViolation, "suspended")
		return
	}
	ch := s.hub.subscribe(u.ID, dev.ID)
	defer s.hub.unsubscribe(u.ID, dev.ID, ch)
	ctx = c.CloseRead(r.Context())
	if err := writeWS(ctx, c, map[string]any{"type": "ready"}); err != nil {
		return
	}
	ping := time.NewTicker(30 * time.Second)
	defer ping.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-s.stop:
			return
		case e, open := <-ch:
			if !open { // Gerät widerrufen
				_ = c.Close(websocket.StatusPolicyViolation, "device revoked")
				return
			}
			var msg map[string]any
			if e.Type == "devices" {
				msg = map[string]any{"type": "devices"}
			} else if e.Type == "sync" {
				msg = map[string]any{"type": "sync", "version": e.Seq}
			} else {
				msg = map[string]any{"type": "message", "seq": e.Seq, "mailbox_id": e.MailboxID, "data": b64.EncodeToString(e.Data)}
			}
			if err := writeWS(ctx, c, msg); err != nil {
				return
			}
		case <-ping.C:
			pc, cancel := context.WithTimeout(ctx, 10*time.Second)
			err := c.Ping(pc)
			cancel()
			if err != nil {
				return
			}
		}
	}
}

func writeWS(ctx context.Context, c *websocket.Conn, v any) error {
	b, _ := json.Marshal(v)
	wc, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	return c.Write(wc, websocket.MessageText, b)
}
