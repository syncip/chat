package server

import (
	"crypto/sha256"
	"errors"
	"io"
	"net/http"
	"strconv"

	"github.com/syncip/chat/server/internal/store"
)

type mailboxView struct {
	MailboxID string `json:"mailbox_id"`
	Intro     bool   `json:"intro"`
}

func (s *Server) listMailboxes(w http.ResponseWriter, r *http.Request, u *store.User) {
	mbs, err := s.st.ListMailboxes(u.ID, devOf(r).ID)
	if err != nil {
		writeErr(w, 500, "internal error")
		return
	}
	out := make([]mailboxView, 0, len(mbs))
	for _, m := range mbs {
		out = append(out, mailboxView{m.ID, m.Intro})
	}
	writeJSON(w, 200, out)
}

// createMailbox erzeugt ein neues Postfach (Capability) **für das anfragende Gerät**, z. B. eines pro Unterhaltung.
func (s *Server) createMailbox(w http.ResponseWriter, r *http.Request, u *store.User) {
	// Optional {"scope":"account"}: kontoweites Postfach (z. B. Intro-Link), Zustellung an alle Geräte.
	var req struct {
		Scope string `json:"scope"`
	}
	if r.ContentLength > 0 && !readJSON(w, r, 1<<10, &req) {
		return
	}
	account := req.Scope == "account"
	dev := devOf(r).ID
	if account {
		dev = ""
	}
	tok := randID(24)
	m := store.MailboxInit{ID: randID(16), TokenHash: hashToken(tok)}
	switch err := s.st.CreateMailbox(u.ID, dev, account, m, s.conf().MaxMailboxes); {
	case errors.Is(err, store.ErrConflict):
		writeErr(w, 429, "mailbox limit reached")
	case err != nil:
		writeErr(w, 500, "internal error")
	default:
		writeJSON(w, 201, map[string]string{"mailbox_id": m.ID, "send_token": tok})
	}
}

// deleteMailbox widerruft die Capability. Wer das Token hat, kann nichts mehr senden (= Blockieren).
func (s *Server) deleteMailbox(w http.ResponseWriter, r *http.Request, u *store.User) {
	if err := s.st.DeleteMailbox(u.ID, devOf(r).ID, r.PathValue("id")); err != nil {
		writeErr(w, 404, "not found")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

var errBlocked = errors.New("blocked")

// deliverLocal legt die Nachricht ins Postfach. `origin` ist die Herkunfts-Domain (leer bei direktem Einwurf).
func (s *Server) deliverLocal(mailboxID, token string, data []byte, origin string) error {
	if int64(len(data)) > s.conf().MaxEnvelopeSize || len(data) == 0 {
		return errTooLarge
	}
	mb, err := s.st.Mailbox(mailboxID)
	if err != nil || !tokenEq(mb.TokenHash, token) {
		return store.ErrNotFound
	}
	if origin != "" {
		u, err := s.st.UserByID(mb.UserID)
		if err != nil {
			return store.ErrNotFound
		}
		h := sha256.Sum256([]byte(origin))
		blocked, err := s.st.OriginBlocked(u.ID, u.FilterMode, h[:])
		if err != nil {
			return err
		}
		if blocked {
			return errBlocked
		}
	}
	// Kontoweite Postfächer: Kopie für jedes Gerät; Geräte-Postfächer: nur dieses Gerät.
	seqs, err := s.st.Deliver(mb.UserID, mb.ID, mb.DeviceID, data)
	if err != nil {
		return err
	}
	for dev, seq := range seqs {
		s.hub.publish(mb.UserID, dev, event{Type: "message", Seq: seq, MailboxID: mb.ID, Data: data})
	}
	return nil
}

var errTooLarge = errors.New("too large")

func (s *Server) deliverStatus(w http.ResponseWriter, err error) {
	switch {
	case err == nil, errors.Is(err, errBlocked):
		// Blockierte Absender erfahren nichts: gleiche Antwort wie bei Erfolg.
		w.WriteHeader(http.StatusAccepted)
	case errors.Is(err, errTooLarge):
		writeErr(w, 413, "invalid size")
	case errors.Is(err, store.ErrNotFound):
		writeErr(w, 404, "no such mailbox")
	default:
		s.log.Error("deliver", "err", err)
		writeErr(w, 500, "internal error")
	}
}

// putMessage: anonymer Einwurf per Capability (Sealed Sender; der Server erfährt den Absender nicht).
func (s *Server) putMessage(w http.ResponseWriter, r *http.Request) {
	data, err := io.ReadAll(http.MaxBytesReader(w, r.Body, s.conf().MaxEnvelopeSize+1))
	if err != nil {
		writeErr(w, 413, "invalid size")
		return
	}
	s.deliverStatus(w, s.deliverLocal(r.PathValue("id"), r.Header.Get("X-Send-Token"), data, ""))
}

type msgView struct {
	Seq       int64  `json:"seq"`
	MailboxID string `json:"mailbox_id"`
	Data      string `json:"data"`
}

func (s *Server) getMessages(w http.ResponseWriter, r *http.Request, u *store.User) {
	after, _ := strconv.ParseInt(r.URL.Query().Get("after"), 10, 64)
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	if limit <= 0 || limit > 200 {
		limit = 100
	}
	msgs, err := s.st.Messages(u.ID, devOf(r).ID, after, limit)
	if err != nil {
		writeErr(w, 500, "internal error")
		return
	}
	out := make([]msgView, 0, len(msgs))
	for _, m := range msgs {
		out = append(out, msgView{m.Seq, m.MailboxID, b64.EncodeToString(m.Data)})
	}
	writeJSON(w, 200, out)
}

func (s *Server) ackMessages(w http.ResponseWriter, r *http.Request, u *store.User) {
	upto, err := strconv.ParseInt(r.URL.Query().Get("upto"), 10, 64)
	if err != nil || upto < 0 {
		writeErr(w, 400, "invalid upto")
		return
	}
	if err := s.st.AckMessages(u.ID, devOf(r).ID, upto); err != nil {
		writeErr(w, 500, "internal error")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ---- KeyPackages ----

type kpReq struct {
	KeyPackages []string `json:"keypackages"`
	LastResort  string   `json:"last_resort"`
}

func (s *Server) putKeyPackages(w http.ResponseWriter, r *http.Request, u *store.User) {
	var req kpReq
	if !readJSON(w, r, 4<<20, &req) {
		return
	}
	kps, last, ok := s.decodeKPs(w, req.KeyPackages, req.LastResort)
	if !ok {
		return
	}
	switch err := s.st.AddKeyPackages(u.ID, devOf(r).ID, kps, last, s.conf().MaxKeyPackages); {
	case errors.Is(err, store.ErrConflict):
		writeErr(w, 409, "too many keypackages")
	case err != nil:
		writeErr(w, 500, "internal error")
	default:
		w.WriteHeader(http.StatusNoContent)
	}
}

func (s *Server) countKeyPackages(w http.ResponseWriter, r *http.Request, u *store.User) {
	n, err := s.st.KeyPackageCount(u.ID, devOf(r).ID)
	if err != nil {
		writeErr(w, 500, "internal error")
		return
	}
	writeJSON(w, 200, map[string]int{"count": n})
}

// getKeyPackages liefert je aktivem Gerät des Kontos ein KeyPackage (öffentlich).
func (s *Server) getKeyPackages(w http.ResponseWriter, r *http.Request) {
	u, err := s.st.UserByName(r.PathValue("name"))
	if err != nil {
		writeErr(w, 404, "not found")
		return
	}
	kps, err := s.st.TakeKeyPackages(u.ID, r.URL.Query()["device"]) // optional: nur bestimmte Geräte
	if err != nil {
		writeErr(w, 404, "no keypackage")
		return
	}
	type dk struct {
		Device     string `json:"device"`
		KeyPackage string `json:"keypackage"`
	}
	out := make([]dk, 0, len(kps))
	for _, k := range kps {
		out = append(out, dk{k.DeviceID, b64.EncodeToString(k.Data)})
	}
	writeJSON(w, 200, map[string]any{"ik": b64.EncodeToString(u.IK), "devices": out})
}

// resolveKeyPackages: holt die KeyPackages aller Geräte von `name@domain` (lokal oder per Föderation).
func (s *Server) resolveKeyPackages(w http.ResponseWriter, r *http.Request, _ *store.User) {
	name, domain, ok := splitAddress(r.PathValue("address"))
	if !ok {
		writeErr(w, 400, "invalid address")
		return
	}
	if s.isLocal(domain) {
		r.SetPathValue("name", name)
		s.getKeyPackages(w, r)
		return
	}
	body, err := s.fed.fetchKeyPackages(r.Context(), domain, name, r.URL.Query()["device"])
	if err != nil {
		writeErr(w, 404, "not found")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write(body)
}

// ---- Relay ----

type relayReq struct {
	Domain    string `json:"domain"`
	MailboxID string `json:"mailbox_id"`
	SendToken string `json:"send_token"`
	Data      string `json:"data"`
}

// relay stellt eine Nachricht im Auftrag des Nutzers zu (verbirgt dessen IP vor dem Remote-Server).
func (s *Server) relay(w http.ResponseWriter, r *http.Request, _ *store.User) {
	var req relayReq
	if !readJSON(w, r, s.conf().MaxEnvelopeSize*2+4096, &req) {
		return
	}
	data, err := b64.DecodeString(req.Data)
	if err != nil {
		writeErr(w, 400, "invalid data")
		return
	}
	domain := normDomain(req.Domain)
	if s.isLocal(domain) {
		s.deliverStatus(w, s.deliverLocal(req.MailboxID, req.SendToken, data, s.conf().Domain))
		return
	}
	if !validDomain(domain) || !s.fed.allowed(domain) {
		writeErr(w, 403, "federation not permitted")
		return
	}
	if int64(len(data)) > s.conf().MaxEnvelopeSize {
		writeErr(w, 413, "invalid size")
		return
	}
	code, err := s.fed.deliver(r.Context(), domain, req.MailboxID, req.SendToken, data)
	if err != nil {
		writeErr(w, 502, "remote server unreachable")
		return
	}
	if code >= 200 && code < 300 {
		w.WriteHeader(http.StatusAccepted)
		return
	}
	writeErr(w, code, "remote refused")
}
