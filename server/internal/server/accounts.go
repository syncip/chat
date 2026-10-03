package server

import (
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/syncip/chat/server/internal/store"
)

type registerReq struct {
	Invite      string   `json:"invite"`
	Name        string   `json:"name"`
	IK          string   `json:"ik"`  // base64 Ed25519 Public Key
	Ts          int64    `json:"ts"`  // Unix-Sekunden
	Sig         string   `json:"sig"` // Signatur über "CHAT-REGISTER-V1\n<domain>\n<name>\n<ts>"
	PoW         string   `json:"pow"` // Nonce für offene Registrierung
	KeyPackages []string `json:"keypackages"`
	LastResort  string   `json:"last_resort"`
	Device      devReq   `json:"device"`
	Inbox       inboxReq `json:"inbox"`
}

// devReq: Gerät mit Zertifikat des Konto-Schlüssels (AIK) über „CHAT-DEVICE-V1\n<adresse>\n<geräte-id>\n<dpk-hex>“.
type devReq struct {
	ID   string `json:"id"`
	DPK  string `json:"dpk"`
	Cert string `json:"cert"`
}

// inboxReq: Geräte-Postfach (ID und Token deterministisch aus dem AIK abgeleitet; der Server sieht nur den Token-Hash).
type inboxReq struct {
	MailboxID string `json:"mailbox_id"`
	TokenHash string `json:"token_hash"`
}

var inboxIDRe = regexp.MustCompile(`^[a-z2-7]{26}$`)

func certMessage(address, devID string, dpk []byte) []byte {
	return []byte("CHAT-DEVICE-V1\n" + address + "\n" + devID + "\n" + hex.EncodeToString(dpk))
}

// parseDevice prüft Gerät und Inbox; `ik` ist der Konto-Schlüssel (AIK), der das Zertifikat ausgestellt haben muss.
func (s *Server) parseDevice(w http.ResponseWriter, address string, ik []byte, d devReq, in inboxReq) (store.Device, store.Inbox, bool) {
	dpk, e1 := b64.DecodeString(d.DPK)
	cert, e2 := b64.DecodeString(d.Cert)
	th, e3 := b64.DecodeString(in.TokenHash)
	if !devRe.MatchString(d.ID) || e1 != nil || e2 != nil || e3 != nil || len(dpk) != ed25519.PublicKeySize ||
		len(cert) != ed25519.SignatureSize || len(th) != sha256.Size || !inboxIDRe.MatchString(in.MailboxID) {
		writeErr(w, 400, "invalid device")
		return store.Device{}, store.Inbox{}, false
	}
	if !ed25519.Verify(ik, certMessage(address, d.ID, dpk), cert) {
		writeErr(w, 400, "invalid device certificate")
		return store.Device{}, store.Inbox{}, false
	}
	return store.Device{ID: d.ID, DPK: dpk, Cert: cert}, store.Inbox{MailboxID: in.MailboxID, TokenHash: th}, true
}

func (s *Server) register(w http.ResponseWriter, r *http.Request) {
	if s.cfg.Registration == "closed" {
		writeErr(w, http.StatusForbidden, "registration closed")
		return
	}
	var req registerReq
	if !readJSON(w, r, 4<<20, &req) {
		return
	}
	if !nameRe.MatchString(req.Name) {
		writeErr(w, 400, "invalid name")
		return
	}
	ik, err := b64.DecodeString(req.IK)
	if err != nil || len(ik) != ed25519.PublicKeySize {
		writeErr(w, 400, "invalid ik")
		return
	}
	if d := time.Since(time.Unix(req.Ts, 0)); d > maxSkew || d < -maxSkew {
		writeErr(w, 400, "clock skew")
		return
	}
	sig, err := b64.DecodeString(req.Sig)
	msg := []byte("CHAT-REGISTER-V1\n" + s.cfg.Domain + "\n" + req.Name + "\n" + strconv.FormatInt(req.Ts, 10))
	if err != nil || !ed25519.Verify(ik, msg, sig) {
		writeErr(w, 400, "invalid signature")
		return
	}
	var inviteHash []byte
	switch s.cfg.Registration {
	case "invite":
		if req.Invite == "" {
			writeErr(w, 403, "invite required")
			return
		}
		inviteHash = hashToken(strings.TrimSpace(req.Invite))
	case "open":
		if !powOK(req.Name, strconv.FormatInt(req.Ts, 10), req.PoW, s.powBits()) {
			writeErr(w, 403, "proof of work required")
			return
		}
	}
	kps, last, ok := s.decodeKPs(w, req.KeyPackages, req.LastResort)
	if !ok {
		return
	}
	dev, inbox, ok := s.parseDevice(w, req.Name+"@"+s.cfg.Domain, ik, req.Device, req.Inbox)
	if !ok {
		return
	}
	tok := randID(24)
	intro := store.MailboxInit{ID: randID(16), TokenHash: hashToken(tok)}
	switch err := s.st.Register(inviteHash, req.Name, ik, dev, inbox, intro, kps, last); {
	case errors.Is(err, store.ErrNotFound):
		writeErr(w, 403, "invalid or expired invite")
	case errors.Is(err, store.ErrConflict):
		writeErr(w, 409, "name taken")
	case err != nil:
		s.log.Error("register", "err", err)
		writeErr(w, 500, "internal error")
	default:
		writeJSON(w, 201, map[string]any{
			"address": req.Name + "@" + s.cfg.Domain,
			"intro":   map[string]string{"mailbox_id": intro.ID, "send_token": tok},
		})
	}
}

func (s *Server) decodeKPs(w http.ResponseWriter, in []string, lastResort string) ([][]byte, []byte, bool) {
	if len(in) > s.cfg.MaxKeyPackages {
		writeErr(w, 400, "too many keypackages")
		return nil, nil, false
	}
	var out [][]byte
	for _, k := range in {
		b, err := b64.DecodeString(k)
		if err != nil || len(b) == 0 || len(b) > 16<<10 {
			writeErr(w, 400, "invalid keypackage")
			return nil, nil, false
		}
		out = append(out, b)
	}
	var last []byte
	if lastResort != "" {
		b, err := b64.DecodeString(lastResort)
		if err != nil || len(b) == 0 || len(b) > 16<<10 {
			writeErr(w, 400, "invalid keypackage")
			return nil, nil, false
		}
		last = b
	}
	return out, last, true
}

// ---- Einladungen ----

type inviteReq struct {
	Uses    int `json:"uses"`
	TTLDays int `json:"ttl_days"`
}

func (s *Server) newInvite(w http.ResponseWriter, r *http.Request, by int64) {
	req := inviteReq{Uses: 1, TTLDays: 7}
	if r.ContentLength != 0 {
		if !readJSON(w, r, 1<<10, &req) {
			return
		}
	}
	if req.Uses < 1 || req.Uses > 1000 || req.TTLDays < 1 || req.TTLDays > 365 {
		writeErr(w, 400, "invalid parameters")
		return
	}
	tok := randID(16)
	if err := s.st.CreateInvite(hashToken(tok), time.Duration(req.TTLDays)*24*time.Hour, req.Uses, by); err != nil {
		writeErr(w, 500, "internal error")
		return
	}
	writeJSON(w, 201, map[string]any{"invite": tok})
}

func (s *Server) adminInvite(w http.ResponseWriter, r *http.Request) {
	k := r.Header.Get("X-Admin-Key")
	if s.cfg.AdminKey == "" || len(k) != len(s.cfg.AdminKey) || !constEq(k, s.cfg.AdminKey) {
		writeErr(w, 401, "unauthorized")
		return
	}
	s.newInvite(w, r, 0)
}

func (s *Server) userInvite(w http.ResponseWriter, r *http.Request, u *store.User) {
	if !s.cfg.UserInvites {
		writeErr(w, 403, "user invites disabled")
		return
	}
	s.newInvite(w, r, u.ID)
}

func constEq(a, b string) bool {
	ha, hb := sha256.Sum256([]byte(a)), sha256.Sum256([]byte(b))
	var d byte
	for i := range ha {
		d |= ha[i] ^ hb[i]
	}
	return d == 0
}

// BootstrapInvite erzeugt beim ersten Start (keine Nutzer) eine Einladung und gibt sie zurück.
func (s *Server) BootstrapInvite() (string, error) {
	n, err := s.st.UserCount()
	if err != nil || n > 0 || s.cfg.Registration != "invite" {
		return "", err
	}
	tok := randID(16)
	return tok, s.st.CreateInvite(hashToken(tok), 24*time.Hour, 1, 0)
}

// ---- Filter (optional serverseitig; Details siehe docs/PROTOCOL.md §8) ----

type filtersReq struct {
	Mode    string   `json:"mode"`    // off | block | allow
	Domains []string `json:"domains"` // sha256(domain) base64
}

func (s *Server) putFilters(w http.ResponseWriter, r *http.Request, u *store.User) {
	var req filtersReq
	if !readJSON(w, r, 1<<20, &req) {
		return
	}
	if req.Mode != "off" && req.Mode != "block" && req.Mode != "allow" || len(req.Domains) > 10000 {
		writeErr(w, 400, "invalid filters")
		return
	}
	var hs [][]byte
	for _, d := range req.Domains {
		b, err := b64.DecodeString(d)
		if err != nil || len(b) != sha256.Size {
			writeErr(w, 400, "invalid hash")
			return
		}
		hs = append(hs, b)
	}
	if err := s.st.ReplaceFilters(u.ID, hs); err != nil {
		writeErr(w, 500, "internal error")
		return
	}
	if err := s.st.SetFilterMode(u.ID, req.Mode); err != nil {
		writeErr(w, 500, "internal error")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ---- Geräte (Multi-Device) ----

type addDeviceReq struct {
	Name        string   `json:"name"`
	Ts          int64    `json:"ts"`
	Sig         string   `json:"sig"` // AIK-Signatur über "CHAT-ADD-DEVICE-V1\n<domain>\n<name>\n<ts>\n<geräte-id>"
	Device      devReq   `json:"device"`
	Inbox       inboxReq `json:"inbox"`
	KeyPackages []string `json:"keypackages"`
	LastResort  string   `json:"last_resort"`
}

// addDevice: neues Gerät eines bestehenden Kontos. Beglaubigt durch den Konto-Schlüssel (Backup-Datei), kein Altgerät nötig.
func (s *Server) addDevice(w http.ResponseWriter, r *http.Request) {
	var req addDeviceReq
	if !readJSON(w, r, 4<<20, &req) {
		return
	}
	u, err := s.st.UserByName(req.Name)
	if err != nil {
		writeErr(w, 401, "unauthorized")
		return
	}
	if d := time.Since(time.Unix(req.Ts, 0)); d > maxSkew || d < -maxSkew {
		writeErr(w, 400, "clock skew")
		return
	}
	sig, err := b64.DecodeString(req.Sig)
	msg := []byte("CHAT-ADD-DEVICE-V1\n" + s.cfg.Domain + "\n" + req.Name + "\n" + strconv.FormatInt(req.Ts, 10) + "\n" + req.Device.ID)
	if err != nil || !ed25519.Verify(u.IK, msg, sig) {
		writeErr(w, 401, "unauthorized")
		return
	}
	if s.nonces.seen("adddev:" + req.Sig) {
		writeErr(w, 401, "unauthorized")
		return
	}
	kps, last, ok := s.decodeKPs(w, req.KeyPackages, req.LastResort)
	if !ok {
		return
	}
	dev, inbox, ok := s.parseDevice(w, req.Name+"@"+s.cfg.Domain, u.IK, req.Device, req.Inbox)
	if !ok {
		return
	}
	switch err := s.st.AddDevice(u.ID, dev, inbox, kps, last, s.cfg.MaxDevices); {
	case errors.Is(err, store.ErrLimit):
		writeErr(w, 429, "device limit reached")
	case errors.Is(err, store.ErrConflict):
		writeErr(w, 409, "device exists")
	case err != nil:
		s.log.Error("add device", "err", err)
		writeErr(w, 500, "internal error")
	default:
		s.notifyDevices(u.ID)
		writeJSON(w, http.StatusCreated, map[string]string{"status": "ok"})
	}
}

type deviceView struct {
	ID        string `json:"id"`
	DPK       string `json:"dpk"`
	Cert      string `json:"cert"`
	CreatedAt int64  `json:"created_at"`
	Current   bool   `json:"current"`
}

func (s *Server) listDevices(w http.ResponseWriter, r *http.Request, u *store.User) {
	ds, err := s.st.ListDevices(u.ID)
	if err != nil {
		writeErr(w, 500, "internal error")
		return
	}
	cur := devOf(r).ID
	out := make([]deviceView, 0, len(ds))
	for _, d := range ds {
		out = append(out, deviceView{d.ID, b64.EncodeToString(d.DPK), b64.EncodeToString(d.Cert), d.CreatedAt, d.ID == cur})
	}
	writeJSON(w, 200, out)
}

// revokeDevice widerruft ein Gerät (Anmeldung, KeyPackages, Postfach). Das letzte Gerät kann nicht widerrufen werden.
func (s *Server) revokeDevice(w http.ResponseWriter, r *http.Request, u *store.User) {
	id := r.PathValue("id")
	ds, err := s.st.ListDevices(u.ID)
	if err != nil {
		writeErr(w, 500, "internal error")
		return
	}
	if len(ds) <= 1 {
		writeErr(w, 409, "cannot revoke the last device")
		return
	}
	switch err := s.st.RevokeDevice(u.ID, id); {
	case errors.Is(err, store.ErrNotFound):
		writeErr(w, 404, "not found")
	case err != nil:
		writeErr(w, 500, "internal error")
	default:
		s.hub.kick(u.ID, id)
		s.notifyDevices(u.ID)
		w.WriteHeader(http.StatusNoContent)
	}
}

// notifyDevices meldet den Geräten des Kontos, dass sich die Geräteliste geändert hat.
func (s *Server) notifyDevices(uid int64) {
	ds, err := s.st.ListDevices(uid)
	if err != nil {
		return
	}
	for _, d := range ds {
		s.hub.publish(uid, d.ID, event{Type: "devices"})
	}
}
