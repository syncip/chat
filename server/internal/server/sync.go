package server

import (
	"net/http"

	"github.com/syncip/chat/server/internal/store"
)

// Konto-Sync: ein vom Client mit einem aus dem Konto-Schlüssel (AIK) abgeleiteten Schlüssel verschlüsselter Blob
// (Kanäle, Einstellungen, Kontakte). Der Server speichert ihn nur versioniert (Compare-and-Swap) und benachrichtigt die anderen Geräte.

const maxSyncBlob = 512 << 10

func (s *Server) getSync(w http.ResponseWriter, r *http.Request, u *store.User) {
	v, data, err := s.st.GetSync(u.ID)
	if err != nil {
		writeErr(w, 500, "internal error")
		return
	}
	writeJSON(w, 200, map[string]any{"version": v, "data": b64.EncodeToString(data)})
}

func (s *Server) putSync(w http.ResponseWriter, r *http.Request, u *store.User) {
	var in struct {
		BaseVersion int64  `json:"base_version"`
		Data        string `json:"data"`
	}
	if !readJSON(w, r, maxSyncBlob*2, &in) {
		return
	}
	data, err := b64.DecodeString(in.Data)
	if err != nil || len(data) == 0 || len(data) > maxSyncBlob {
		writeErr(w, 400, "invalid data")
		return
	}
	nv, ok, err := s.st.PutSync(u.ID, in.BaseVersion, data)
	if err != nil {
		writeErr(w, 500, "internal error")
		return
	}
	if !ok {
		cur, d, _ := s.st.GetSync(u.ID)
		writeJSON(w, 409, map[string]any{"error": "conflict", "version": cur, "data": b64.EncodeToString(d)})
		return
	}
	if devs, err := s.st.ListDevices(u.ID); err == nil {
		self := devOf(r)
		for _, d := range devs {
			if self == nil || d.ID != self.ID {
				s.hub.publish(u.ID, d.ID, event{Type: "sync", Seq: nv})
			}
		}
	}
	writeJSON(w, 200, map[string]any{"version": nv})
}
