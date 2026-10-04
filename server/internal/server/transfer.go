package server

import (
	"net/http"
	"sync"
	"time"

	"github.com/syncip/chat/server/internal/store"
)

// Einmal-Übergabe für „Gerät per QR-Code anmelden“: Ein angemeldetes Gerät legt einen clientseitig verschlüsselten Blob ab
// (Schlüssel nur im QR-Code), das neue Gerät holt ihn genau einmal ab. Nur im Speicher, kurze Lebensdauer; der Server sieht nur Chiffretext.
const (
	transferTTL     = 5 * time.Minute
	transferMaxSize = 256 << 10
	transferPerUser = 3
	transferTotal   = 1000
)

type transferEntry struct {
	user string
	data []byte
	exp  time.Time
}

type transferStore struct {
	mu sync.Mutex
	m  map[string]*transferEntry
}

func newTransferStore() *transferStore { return &transferStore{m: map[string]*transferEntry{}} }

func (t *transferStore) sweepLocked(now time.Time) {
	for k, e := range t.m {
		if now.After(e.exp) {
			delete(t.m, k)
		}
	}
}

func (t *transferStore) sweep() {
	t.mu.Lock()
	t.sweepLocked(time.Now())
	t.mu.Unlock()
}

func (s *Server) postTransfer(w http.ResponseWriter, r *http.Request, u *store.User) {
	var in struct {
		Data string `json:"data"`
	}
	if !readJSON(w, r, transferMaxSize*2, &in) {
		return
	}
	data, err := b64.DecodeString(in.Data)
	if err != nil || len(data) == 0 || len(data) > transferMaxSize {
		writeErr(w, 400, "invalid data")
		return
	}
	t := s.xfer
	t.mu.Lock()
	defer t.mu.Unlock()
	now := time.Now()
	t.sweepLocked(now)
	n := 0
	for _, e := range t.m {
		if e.user == u.Name {
			n++
		}
	}
	if n >= transferPerUser || len(t.m) >= transferTotal {
		writeErr(w, 429, "too many pending transfers")
		return
	}
	id := randID(16)
	t.m[id] = &transferEntry{user: u.Name, data: data, exp: now.Add(transferTTL)}
	writeJSON(w, 201, map[string]any{"id": id, "expires_in": int(transferTTL.Seconds())})
}

// getTransfer: ohne Anmeldung (das neue Gerät hat noch keine); die ID ist ein 128-Bit-Geheimnis und wird beim Abruf verbraucht.
func (s *Server) getTransfer(w http.ResponseWriter, r *http.Request) {
	t := s.xfer
	t.mu.Lock()
	e, ok := t.m[r.PathValue("id")]
	if ok {
		delete(t.m, r.PathValue("id"))
	}
	t.mu.Unlock()
	if !ok || time.Now().After(e.exp) {
		writeErr(w, 404, "not found")
		return
	}
	writeJSON(w, 200, map[string]any{"data": b64.EncodeToString(e.data)})
}
