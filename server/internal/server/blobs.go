package server

import (
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"

	"github.com/syncip/chat/server/internal/store"
)

var blobIDRe = regexp.MustCompile(`^[a-z2-7]{20,64}$`)

func (s *Server) blobPath(id string) string { return filepath.Join(s.blobDir, id[:2], id) }

func (s *Server) quotaFor(u *store.User) int64 {
	if u.Quota > 0 {
		return u.Quota
	}
	return s.conf().UserQuota
}

// postBlob nimmt eine clientseitig verschlüsselte Datei entgegen. Der Server sieht nur Chiffretext.
func (s *Server) postBlob(w http.ResponseWriter, r *http.Request, u *store.User) {
	size := r.ContentLength
	if size <= 0 {
		writeErr(w, 411, "content-length required")
		return
	}
	if size > s.conf().MaxFileSize+s.conf().MaxFileSize/1024*16+64 { // Chunk-Tags (16 B pro 64 KiB) einrechnen
		writeErr(w, 413, "file too large")
		return
	}
	used, err := s.st.BlobUsage(u.ID)
	if err != nil {
		writeErr(w, 500, "internal error")
		return
	}
	if used+size > s.quotaFor(u) {
		writeErr(w, 507, "quota exceeded")
		return
	}
	id := randID(24)
	p := s.blobPath(id)
	if err := os.MkdirAll(filepath.Dir(p), 0o700); err != nil {
		writeErr(w, 500, "internal error")
		return
	}
	f, err := os.OpenFile(p, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
	if err != nil {
		writeErr(w, 500, "internal error")
		return
	}
	n, err := io.Copy(f, http.MaxBytesReader(w, r.Body, size))
	cerr := f.Close()
	if err != nil || cerr != nil || n != size {
		_ = os.Remove(p)
		writeErr(w, 400, "upload incomplete")
		return
	}
	if err := s.st.AddBlob(u.ID, id, size, s.conf().BlobRetention); err != nil {
		_ = os.Remove(p)
		writeErr(w, 500, "internal error")
		return
	}
	writeJSON(w, 201, map[string]any{"blob_id": id, "size": size})
}

// getBlob: Download per unratbarer ID (Capability).
func (s *Server) getBlob(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !blobIDRe.MatchString(id) {
		writeErr(w, 404, "not found")
		return
	}
	if _, err := s.st.BlobOwner(id); err != nil {
		writeErr(w, 404, "not found")
		return
	}
	f, err := os.Open(s.blobPath(id))
	if err != nil {
		writeErr(w, 404, "not found")
		return
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		writeErr(w, 500, "internal error")
		return
	}
	h := w.Header()
	h.Set("Content-Type", "application/octet-stream")
	h.Set("Content-Disposition", "attachment")
	h.Set("Content-Security-Policy", "sandbox; default-src 'none'")
	h.Set("Cross-Origin-Resource-Policy", "cross-origin")
	http.ServeContent(w, r, "", st.ModTime(), f)
}

func (s *Server) deleteBlob(w http.ResponseWriter, r *http.Request, u *store.User) {
	id := r.PathValue("id")
	if !blobIDRe.MatchString(id) {
		writeErr(w, 404, "not found")
		return
	}
	if err := s.st.DeleteBlob(u.ID, id); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeErr(w, 404, "not found")
		} else {
			writeErr(w, 500, "internal error")
		}
		return
	}
	_ = os.Remove(s.blobPath(id))
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) quota(w http.ResponseWriter, r *http.Request, u *store.User) {
	used, err := s.st.BlobUsage(u.ID)
	if err != nil {
		writeErr(w, 500, "internal error")
		return
	}
	writeJSON(w, 200, map[string]int64{"used": used, "quota": s.quotaFor(u)})
}
