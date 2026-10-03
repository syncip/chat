package server

import (
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strings"
)

const csp = "default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self'; img-src 'self' blob: data:; " +
	"font-src 'self'; connect-src 'self' https: wss: ws:; media-src blob:; worker-src 'self' blob:; manifest-src 'self'; " +
	"base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

// webHandler liefert das Web-Bundle (SPA) mit strikten Sicherheits-Headern aus.
func (s *Server) webHandler() http.Handler {
	root := s.cfg.WebDir
	fsrv := http.FileServer(http.Dir(root))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("Content-Security-Policy", csp)
		h.Set("X-Frame-Options", "DENY")
		h.Set("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
		h.Set("Cross-Origin-Opener-Policy", "same-origin")
		p := path.Clean("/" + r.URL.Path)
		full := filepath.Join(root, filepath.FromSlash(p))
		if st, err := os.Stat(full); err != nil || st.IsDir() {
			if strings.Contains(path.Base(p), ".") { // fehlende Datei → 404 statt SPA-Fallback
				http.NotFound(w, r)
				return
			}
			r.URL.Path = "/"
		}
		if strings.HasSuffix(p, ".wasm") {
			h.Set("Content-Type", "application/wasm")
		}
		fsrv.ServeHTTP(w, r)
	})
}
