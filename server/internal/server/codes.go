package server

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"regexp"
	"strings"
	"time"

	"github.com/syncip/chat/server/internal/store"
)

var b64url = base64.RawURLEncoding

var (
	codeRe    = regexp.MustCompile(`^[a-z0-9][a-z0-9_-]{2,39}$`)
	cardChars = regexp.MustCompile(`^[A-Za-z0-9_-]+$`)
)

func validCard(c string) bool { return len(c) >= 20 && len(c) <= 2048 && cardChars.MatchString(c) }

// decodeCardAddr liest die Adresse und die Domain aus einer Kontaktkarte (base64url-JSON {a,d,m,t,k}).
func decodeCardAddr(card string) (addr, domain string, ok bool) {
	b, err := b64url.DecodeString(card)
	if err != nil {
		return "", "", false
	}
	var j struct {
		A string `json:"a"`
		D string `json:"d"`
		M string `json:"m"`
		T string `json:"t"`
		K string `json:"k"`
	}
	if json.Unmarshal(b, &j) != nil || j.A == "" || j.D == "" || j.M == "" || j.T == "" || j.K == "" {
		return "", "", false
	}
	return j.A, j.D, true
}

// putCode: das Konto veröffentlicht seine Kontaktkarte unter einem selbst gewählten Code.
func (s *Server) putCode(w http.ResponseWriter, r *http.Request, u *store.User) {
	var in struct {
		Code string `json:"code"`
		Card string `json:"card"`
	}
	if !readJSON(w, r, 4<<10, &in) {
		return
	}
	code := strings.ToLower(strings.TrimSpace(in.Code))
	if !codeRe.MatchString(code) || !validCard(in.Card) {
		writeErr(w, 400, "invalid code (3-40 characters: a-z, 0-9, _ or -)")
		return
	}
	addr, dom, ok := decodeCardAddr(in.Card)
	if !ok || strings.ToLower(addr) != u.Name+"@"+s.conf().Domain || dom != s.conf().Domain {
		writeErr(w, 400, "card does not belong to this account")
		return
	}
	switch err := s.st.SetCode(u.ID, code, in.Card); err {
	case nil:
		writeJSON(w, 200, map[string]any{"code": code})
	case store.ErrConflict:
		writeErr(w, 409, "code already taken")
	default:
		writeErr(w, 500, "internal error")
	}
}

func (s *Server) deleteCode(w http.ResponseWriter, r *http.Request, u *store.User) {
	if err := s.st.DeleteCode(u.ID); err != nil {
		writeErr(w, 500, "internal error")
		return
	}
	writeJSON(w, 200, map[string]any{"status": "ok"})
}

func (s *Server) myCode(w http.ResponseWriter, r *http.Request, u *store.User) {
	c, err := s.st.CodeOf(u.ID)
	if err != nil && err != store.ErrNotFound {
		writeErr(w, 500, "internal error")
		return
	}
	writeJSON(w, 200, map[string]any{"code": c})
}

// resolveCode: öffentlich. `?domain=` fragt (über die Föderation) den Heimserver des Codes statt dieses Servers.
func (s *Server) resolveCode(w http.ResponseWriter, r *http.Request) {
	code := strings.ToLower(r.PathValue("code"))
	if !codeRe.MatchString(code) {
		writeErr(w, 404, "not found")
		return
	}
	dom := normDomain(r.URL.Query().Get("domain"))
	if dom != "" && dom != s.conf().Domain {
		if !validDomain(dom) || !s.fed.allowed(dom) {
			writeErr(w, 404, "not found")
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), 8*time.Second)
		defer cancel()
		req, _ := http.NewRequestWithContext(ctx, "GET", s.fed.base(dom)+"/v1/codes/"+code, nil)
		resp, err := s.fed.client.Do(req)
		if err != nil {
			writeErr(w, 502, "remote server unreachable")
			return
		}
		defer resp.Body.Close()
		if resp.StatusCode != 200 {
			writeErr(w, 404, "not found")
			return
		}
		var v struct {
			Card string `json:"card"`
		}
		if json.NewDecoder(io.LimitReader(resp.Body, 8<<10)).Decode(&v) != nil || !validCard(v.Card) {
			writeErr(w, 502, "bad response")
			return
		}
		if _, d, ok := decodeCardAddr(v.Card); !ok || d != dom {
			writeErr(w, 502, "bad response")
			return
		}
		writeJSON(w, 200, map[string]any{"card": v.Card})
		return
	}
	card, err := s.st.ResolveCode(code)
	if err != nil {
		writeErr(w, 404, "not found")
		return
	}
	writeJSON(w, 200, map[string]any{"card": card})
}
