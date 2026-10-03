package server

import (
	"context"
	"encoding/json"
	"net/http"
	"sync"
	"time"

	"github.com/coder/websocket"
	"github.com/syncip/chat/server/internal/store"
)

type event struct {
	Seq       int64
	MailboxID string
	Data      []byte
}

type hub struct {
	mu   sync.Mutex
	subs map[int64]map[chan event]struct{}
}

func newHub() *hub { return &hub{subs: map[int64]map[chan event]struct{}{}} }

func (h *hub) subscribe(uid int64) chan event {
	ch := make(chan event, 64)
	h.mu.Lock()
	if h.subs[uid] == nil {
		h.subs[uid] = map[chan event]struct{}{}
	}
	h.subs[uid][ch] = struct{}{}
	h.mu.Unlock()
	return ch
}

func (h *hub) unsubscribe(uid int64, ch chan event) {
	h.mu.Lock()
	delete(h.subs[uid], ch)
	if len(h.subs[uid]) == 0 {
		delete(h.subs, uid)
	}
	h.mu.Unlock()
}

// publish blockiert nie: überläuft ein Abonnent, holt er sich die Nachrichten per REST nach.
func (h *hub) publish(uid int64, e event) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for ch := range h.subs[uid] {
		select {
		case ch <- e:
		default:
		}
	}
}

type wsAuth struct {
	Name  string `json:"name"`
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
	var ok bool
	if json.Unmarshal(msg, &a) == nil {
		u, ok = s.verifySig(s.cfg.Domain, "GET", "/v1/stream", map[string]string{
			"name": a.Name, "ts": a.Ts, "nonce": a.Nonce, "sig": a.Sig}, "WS")
	}
	if !ok {
		_ = c.Close(websocket.StatusPolicyViolation, "unauthorized")
		return
	}
	ch := s.hub.subscribe(u.ID)
	defer s.hub.unsubscribe(u.ID, ch)
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
		case e := <-ch:
			if err := writeWS(ctx, c, map[string]any{
				"type": "message", "seq": e.Seq, "mailbox_id": e.MailboxID, "data": b64.EncodeToString(e.Data),
			}); err != nil {
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
