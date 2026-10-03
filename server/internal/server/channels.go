package server

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"image"
	"image/color"
	"image/png"
	"io"
	"math/bits"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
	"github.com/syncip/chat/server/internal/store"
)

// Öffentliche Kanäle (docs/CHANNELS.md). Der Server sieht nur Chiffretext; Mitglieder authentifizieren sich mit ihrem
// Konto-Schlüssel (AIK) über "Authorization: Chan-Sig ik=<b64>,ts=,nonce=,sig=".
// Signiert wird: "CHAT-CHAN-V1\n<domain>\n<METHOD>\n<RequestURI>\n<ts>\n<nonce>\n<hex sha256(body)>".

var postIDRe = regexp.MustCompile(`^[A-Za-z0-9_-]{8,40}$`)

func canonicalChan(domain, method, uri, ts, nonce, bodyHash string) []byte {
	return []byte("CHAT-CHAN-V1\n" + domain + "\n" + method + "\n" + uri + "\n" + ts + "\n" + nonce + "\n" + bodyHash)
}

func canonicalPost(chID, postID string, ts int64, epoch int, data []byte) []byte {
	h := sha256.Sum256(data)
	return []byte("CHAT-POST-V1\n" + chID + "\n" + postID + "\n" + strconv.FormatInt(ts, 10) + "\n" + strconv.Itoa(epoch) + "\n" + hex.EncodeToString(h[:]))
}

// ---- Hub für Kanal-Streams ----

type chanHub struct {
	mu   sync.Mutex
	subs map[string]map[chan int64]struct{}
}

func newChanHub() *chanHub { return &chanHub{subs: map[string]map[chan int64]struct{}{}} }

func (h *chanHub) subscribe(id string) chan int64 {
	ch := make(chan int64, 16)
	h.mu.Lock()
	if h.subs[id] == nil {
		h.subs[id] = map[chan int64]struct{}{}
	}
	h.subs[id][ch] = struct{}{}
	h.mu.Unlock()
	return ch
}

func (h *chanHub) unsubscribe(id string, ch chan int64) {
	h.mu.Lock()
	delete(h.subs[id], ch)
	if len(h.subs[id]) == 0 {
		delete(h.subs, id)
	}
	h.mu.Unlock()
}

func (h *chanHub) publish(id string, seq int64) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for ch := range h.subs[id] {
		select {
		case ch <- seq:
		default:
		}
	}
}

// ---- Authentifizierung ----

type chanHandler func(w http.ResponseWriter, r *http.Request, ch *store.Channel, ik []byte, body []byte)

func (s *Server) chanVerify(domain, method, uri string, a map[string]string, bodyHash string) ([]byte, bool) {
	ikb, err := b64.DecodeString(a["ik"])
	if err != nil || len(ikb) != ed25519.PublicKeySize {
		return nil, false
	}
	ts, nonce := a["ts"], a["nonce"]
	t, err := strconv.ParseInt(ts, 10, 64)
	if err != nil || len(nonce) < 8 || len(nonce) > 64 {
		return nil, false
	}
	if d := time.Since(time.Unix(t, 0)); d > maxSkew || d < -maxSkew {
		return nil, false
	}
	sig, err := b64.DecodeString(a["sig"])
	if err != nil || len(sig) != ed25519.SignatureSize {
		return nil, false
	}
	if !ed25519.Verify(ikb, canonicalChan(domain, method, uri, ts, nonce, bodyHash), sig) {
		return nil, false
	}
	if s.nonces.seen("chan:" + a["ik"] + ":" + nonce) {
		return nil, false
	}
	return ikb, true
}

func parseChanAuth(h string) map[string]string {
	h, ok := strings.CutPrefix(h, "Chan-Sig ")
	if !ok {
		return nil
	}
	m := map[string]string{}
	for _, kv := range strings.Split(h, ",") {
		k, v, ok := strings.Cut(strings.TrimSpace(kv), "=")
		if !ok {
			return nil
		}
		m[k] = v
	}
	return m
}

func (s *Server) chanAuth(h chanHandler) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !s.cfg.Channels {
			writeErr(w, 404, "channels disabled")
			return
		}
		a := parseChanAuth(r.Header.Get("Authorization"))
		if a == nil {
			writeErr(w, 401, "unauthorized")
			return
		}
		body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, int64(s.cfg.MaxPostSize)*2+4096))
		if err != nil {
			writeErr(w, 413, "body too large")
			return
		}
		sum := sha256.Sum256(body)
		ik, ok := s.chanVerify(s.cfg.Domain, r.Method, r.URL.RequestURI(), a, hex.EncodeToString(sum[:]))
		if !ok {
			writeErr(w, 401, "unauthorized")
			return
		}
		ch, err := s.st.Channel(r.PathValue("id"))
		if err != nil {
			writeErr(w, 404, "not found")
			return
		}
		h(w, r, ch, ik, body)
	}
}

// ---- Hilfsfunktionen ----

func now() int64 { return time.Now().Unix() }

func policyJSON(p store.ChannelPolicy) map[string]any {
	return map[string]any{
		"join_mode": p.JoinMode, "pow_bits": p.PowBits, "probation_seconds": p.ProbationSeconds,
		"members_can_write": p.MembersCanWrite, "slow_mode_seconds": p.SlowModeSeconds,
	}
}

func memberJSON(m *store.ChannelMember, p store.ChannelPolicy) map[string]any {
	return map[string]any{
		"ik": b64.EncodeToString(m.IK), "address": m.Address, "role": m.Role, "status": m.Status,
		"joined_at": m.JoinedAt, "muted_until": m.MutedUntil, "can_write": m.CanWrite(p, now()),
	}
}

func entryJSON(e store.LogEntry) map[string]any {
	m := map[string]any{"seq": e.Seq, "type": e.Type, "ts": e.TS, "address": e.Address, "ik": b64.EncodeToString(e.IK)}
	if e.Type == "post" {
		m["post_id"] = e.PostID
		m["epoch"] = e.Epoch
		m["deleted"] = e.Deleted
		if !e.Deleted {
			m["data"] = b64.EncodeToString(e.Data)
			m["sig"] = b64.EncodeToString(e.Sig)
		}
	} else {
		m["kind"] = e.Kind
		m["target"] = b64.EncodeToString(e.Target)
		m["meta"] = e.Meta
	}
	return m
}

// verifyAddress prüft, dass `address` tatsächlich zu diesem AIK gehört (Abfrage beim Heimatserver).
func (s *Server) verifyAddress(ctx context.Context, address string, ik []byte) bool {
	name, domain, ok := splitAddress(address)
	if !ok {
		return false
	}
	var have []byte
	if s.isLocal(domain) {
		u, err := s.st.UserByName(name)
		if err != nil {
			return false
		}
		have = u.IK
	} else {
		c, cancel := context.WithTimeout(ctx, 8*time.Second)
		defer cancel()
		var err error
		have, err = s.fed.fetchUserIK(c, domain, name)
		if err != nil {
			return false
		}
	}
	return bytes.Equal(have, ik)
}

func leadingZeroBits(h [32]byte) int {
	n := 0
	for _, b := range h {
		if b == 0 {
			n += 8
			continue
		}
		return n + bits.LeadingZeros8(b)
	}
	return n
}

func chanPowOK(chID string, ik []byte, nonce string, need int) bool {
	if need <= 0 {
		return true
	}
	if len(nonce) > 64 {
		return false
	}
	return leadingZeroBits(sha256.Sum256([]byte(chID+":"+b64.EncodeToString(ik)+":"+nonce))) >= need
}

// ---- Captcha (zustandslos: HMAC über Kanal, Schlüssel, Ablauf und Lösung) ----

func (s *Server) captchaMAC(chID string, ik []byte, exp int64, answer string) []byte {
	m := hmac.New(sha256.New, append([]byte("captcha:"), s.key.Seed()...))
	m.Write([]byte(chID))
	m.Write(ik)
	var e [8]byte
	binary.BigEndian.PutUint64(e[:], uint64(exp))
	m.Write(e[:])
	m.Write([]byte(answer))
	return m.Sum(nil)
}

func (s *Server) captchaToken(chID string, ik []byte, answer string) string {
	exp := time.Now().Add(10 * time.Minute).Unix()
	var e [8]byte
	binary.BigEndian.PutUint64(e[:], uint64(exp))
	return b64.EncodeToString(append(e[:], s.captchaMAC(chID, ik, exp, answer)...))
}

func (s *Server) captchaOK(chID string, ik []byte, token, answer string) bool {
	raw, err := b64.DecodeString(token)
	if err != nil || len(raw) != 8+32 {
		return false
	}
	exp := int64(binary.BigEndian.Uint64(raw[:8]))
	if time.Now().Unix() > exp {
		return false
	}
	return hmac.Equal(raw[8:], s.captchaMAC(chID, ik, exp, strings.TrimSpace(answer)))
}

// 5x7-Ziffernschrift
var digitFont = [10][7]uint8{
	{0x0E, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0E}, {0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E},
	{0x0E, 0x11, 0x01, 0x02, 0x04, 0x08, 0x1F}, {0x1F, 0x02, 0x04, 0x02, 0x01, 0x11, 0x0E},
	{0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02}, {0x1F, 0x10, 0x1E, 0x01, 0x01, 0x11, 0x0E},
	{0x06, 0x08, 0x10, 0x1E, 0x11, 0x11, 0x0E}, {0x1F, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08},
	{0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E}, {0x0E, 0x11, 0x11, 0x0F, 0x01, 0x02, 0x0C},
}

func captchaPNG(answer string) []byte {
	const sc = 6
	w, h := len(answer)*6*sc+20, 7*sc+24
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	rnd := make([]byte, 64)
	_, _ = rand.Read(rnd)
	bg := color.RGBA{240, 240, 240, 255}
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			img.Set(x, y, bg)
		}
	}
	for i, d := range answer {
		g := digitFont[d-'0']
		ox := 10 + i*6*sc
		oy := 12 + int(rnd[i]%9) - 4
		for row := 0; row < 7; row++ {
			for col := 0; col < 5; col++ {
				if g[row]>>(4-col)&1 == 1 {
					for dy := 0; dy < sc; dy++ {
						for dx := 0; dx < sc; dx++ {
							img.Set(ox+col*sc+dx, oy+row*sc+dy+int(rnd[10+i]%3), color.RGBA{30, 30, 90, 255})
						}
					}
				}
			}
		}
	}
	for i := 0; i < 6; i++ { // Störlinien
		y0, y1 := int(rnd[20+i])%h, int(rnd[30+i])%h
		for x := 0; x < w; x++ {
			y := y0 + (y1-y0)*x/w
			img.Set(x, y, color.RGBA{150, 60, 60, 255})
		}
	}
	var buf bytes.Buffer
	_ = png.Encode(&buf, img)
	return buf.Bytes()
}

// ---- Handler ----

type chanPolicyIn struct {
	JoinMode         string `json:"join_mode"`
	PowBits          int    `json:"pow_bits"`
	ProbationSeconds int64  `json:"probation_seconds"`
	MembersCanWrite  bool   `json:"members_can_write"`
	SlowModeSeconds  int64  `json:"slow_mode_seconds"`
}

func (p chanPolicyIn) policy() store.ChannelPolicy {
	return store.ChannelPolicy{JoinMode: p.JoinMode, PowBits: p.PowBits, ProbationSeconds: p.ProbationSeconds,
		MembersCanWrite: p.MembersCanWrite, SlowModeSeconds: p.SlowModeSeconds}
}

// createChannel: nur Konten dieses Servers (Geräte-Signatur) dürfen Kanäle anlegen; Besitzer ist ihr AIK.
func (s *Server) createChannel(w http.ResponseWriter, r *http.Request, u *store.User) {
	if !s.cfg.Channels {
		writeErr(w, 404, "channels disabled")
		return
	}
	var in struct {
		TitleEnc string       `json:"title_enc"`
		Policy   chanPolicyIn `json:"policy"`
	}
	if !readJSON(w, r, 16<<10, &in) {
		return
	}
	title, err := b64.DecodeString(in.TitleEnc)
	if err != nil || len(title) == 0 || len(title) > 4096 || !in.Policy.policy().Valid() {
		writeErr(w, 400, "invalid channel")
		return
	}
	id := randID(10)
	err = s.st.CreateChannel(store.Channel{ID: id, OwnerUser: u.ID, OwnerIK: u.IK, TitleEnc: title, Policy: in.Policy.policy()}, u.Name+"@"+s.cfg.Domain, s.cfg.MaxChannels)
	if errors.Is(err, store.ErrLimit) {
		writeErr(w, 429, "channel limit reached")
		return
	}
	if err != nil {
		writeErr(w, 500, "internal error")
		return
	}
	_, _ = s.st.AppendEvent(id, "created", u.IK, nil, u.Name+"@"+s.cfg.Domain, nil)
	writeJSON(w, 201, map[string]any{"id": id})
}

func (s *Server) channelInfo(w http.ResponseWriter, r *http.Request) {
	ch, err := s.st.Channel(r.PathValue("id"))
	if err != nil || !s.cfg.Channels {
		writeErr(w, 404, "not found")
		return
	}
	n, _ := s.st.ChannelMemberCount(ch.ID)
	writeJSON(w, 200, map[string]any{
		"id": ch.ID, "title_enc": b64.EncodeToString(ch.TitleEnc), "policy": policyJSON(ch.Policy),
		"owner_ik": b64.EncodeToString(ch.OwnerIK), "members": n, "created_at": ch.CreatedAt,
	})
}

func (s *Server) channelCaptcha(w http.ResponseWriter, r *http.Request) {
	ch, err := s.st.Channel(r.PathValue("id"))
	ik, derr := b64.DecodeString(r.URL.Query().Get("ik"))
	if err != nil || derr != nil || len(ik) != ed25519.PublicKeySize || ch.Policy.JoinMode != "captcha" {
		writeErr(w, 404, "not found")
		return
	}
	d := make([]byte, 5)
	_, _ = rand.Read(d)
	ans := make([]byte, 5)
	for i := range d {
		ans[i] = '0' + d[i]%10
	}
	writeJSON(w, 200, map[string]any{"token": s.captchaToken(ch.ID, ik, string(ans)), "image": b64.EncodeToString(captchaPNG(string(ans)))})
}

func (s *Server) channelJoin(w http.ResponseWriter, r *http.Request, ch *store.Channel, ik []byte, body []byte) {
	var in struct {
		Address       string `json:"address"`
		PowNonce      string `json:"pow_nonce"`
		CaptchaToken  string `json:"captcha_token"`
		CaptchaAnswer string `json:"captcha_answer"`
	}
	if !decodeJSON(w, body, &in) {
		return
	}
	if !s.verifyAddress(r.Context(), in.Address, ik) {
		writeErr(w, 400, "address not verified")
		return
	}
	if m, err := s.st.ChannelMember(ch.ID, ik); err == nil {
		if m.Status == "banned" {
			writeErr(w, 403, "banned")
			return
		}
		writeJSON(w, 200, map[string]any{"me": memberJSON(m, ch.Policy)})
		return
	}
	status := "active"
	switch ch.Policy.JoinMode {
	case "approval":
		status = "pending"
	case "pow":
		if !chanPowOK(ch.ID, ik, in.PowNonce, ch.Policy.PowBits) {
			writeErr(w, 403, "proof of work required")
			return
		}
	case "captcha":
		if !s.captchaOK(ch.ID, ik, in.CaptchaToken, in.CaptchaAnswer) {
			writeErr(w, 403, "captcha failed")
			return
		}
	}
	m, created, err := s.st.JoinChannel(ch.ID, ik, in.Address, status, s.cfg.MaxChannelUsers)
	switch {
	case errors.Is(err, store.ErrBanned):
		writeErr(w, 403, "banned")
		return
	case errors.Is(err, store.ErrLimit):
		writeErr(w, 429, "channel full")
		return
	case err != nil:
		writeErr(w, 500, "internal error")
		return
	}
	if created {
		kind := "join"
		if status == "pending" {
			kind = "join_request"
		}
		seq, _ := s.st.AppendEvent(ch.ID, kind, ik, ik, in.Address, nil)
		s.chans.publish(ch.ID, seq)
	}
	writeJSON(w, 200, map[string]any{"me": memberJSON(m, ch.Policy)})
}

func (s *Server) memberOf(w http.ResponseWriter, ch *store.Channel, ik []byte) *store.ChannelMember {
	m, err := s.st.ChannelMember(ch.ID, ik)
	if err != nil || m.Status == "banned" {
		writeErr(w, 403, "forbidden")
		return nil
	}
	return m
}

func (s *Server) channelLog(w http.ResponseWriter, r *http.Request, ch *store.Channel, ik []byte, _ []byte) {
	m := s.memberOf(w, ch, ik)
	if m == nil {
		return
	}
	resp := map[string]any{"me": memberJSON(m, ch.Policy), "policy": policyJSON(ch.Policy), "title_enc": b64.EncodeToString(ch.TitleEnc), "entries": []any{}}
	if m.Status == "active" {
		after, _ := strconv.ParseInt(r.URL.Query().Get("after"), 10, 64)
		limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
		if limit <= 0 || limit > 200 {
			limit = 100
		}
		es, err := s.st.ChannelLog(ch.ID, after, limit)
		if err != nil {
			writeErr(w, 500, "internal error")
			return
		}
		out := make([]any, 0, len(es))
		for _, e := range es {
			out = append(out, entryJSON(e))
		}
		resp["entries"] = out
	}
	writeJSON(w, 200, resp)
}

func (s *Server) channelPost(w http.ResponseWriter, r *http.Request, ch *store.Channel, ik []byte, body []byte) {
	var in struct {
		PostID string `json:"post_id"`
		TS     int64  `json:"ts"`
		Epoch  int    `json:"epoch"`
		Data   string `json:"data"`
		Sig    string `json:"sig"`
	}
	if !decodeJSON(w, body, &in) {
		return
	}
	data, e1 := b64.DecodeString(in.Data)
	sig, e2 := b64.DecodeString(in.Sig)
	if e1 != nil || e2 != nil || !postIDRe.MatchString(in.PostID) || len(data) == 0 || len(data) > s.cfg.MaxPostSize {
		writeErr(w, 400, "invalid post")
		return
	}
	if d := time.Since(time.UnixMilli(in.TS)); d > 5*time.Minute || d < -5*time.Minute {
		writeErr(w, 400, "bad timestamp")
		return
	}
	if !ed25519.Verify(ik, canonicalPost(ch.ID, in.PostID, in.TS, in.Epoch, data), sig) {
		writeErr(w, 400, "bad signature")
		return
	}
	m, err := s.st.ChannelMember(ch.ID, ik)
	if err != nil || !m.CanWrite(ch.Policy, now()) {
		writeErr(w, 403, "not allowed to write")
		return
	}
	if !m.IsMod() && ch.Policy.SlowModeSeconds > 0 && now() < m.LastPostAt+ch.Policy.SlowModeSeconds {
		writeErr(w, 429, "slow mode")
		return
	}
	seq, err := s.st.AppendPost(ch.ID, in.PostID, ik, m.Address, in.TS, in.Epoch, data, sig)
	if errors.Is(err, store.ErrConflict) {
		writeErr(w, 409, "duplicate post")
		return
	}
	if err != nil {
		writeErr(w, 500, "internal error")
		return
	}
	_ = s.st.TouchPost(ch.ID, ik)
	s.chans.publish(ch.ID, seq)
	writeJSON(w, 201, map[string]any{"seq": seq})
}

func (s *Server) channelMod(w http.ResponseWriter, r *http.Request, ch *store.Channel, ik []byte, body []byte) {
	var in struct {
		Action  string `json:"action"`
		Target  string `json:"target"`
		PostID  string `json:"post_id"`
		Role    string `json:"role"`
		Seconds int64  `json:"seconds"`
	}
	if !decodeJSON(w, body, &in) {
		return
	}
	actor, err := s.st.ChannelMember(ch.ID, ik)
	if err != nil || !actor.IsMod() {
		writeErr(w, 403, "moderator required")
		return
	}
	isOwner := actor.Role == "owner"
	var seq int64
	if in.Action == "delete" {
		author, err := s.st.DeletePost(ch.ID, in.PostID)
		if err != nil {
			writeErr(w, 404, "post not found")
			return
		}
		if t, err := s.st.ChannelMember(ch.ID, author); err == nil && (t.Role == "owner" || (t.Role == "mod" && !isOwner)) && !bytes.Equal(author, ik) {
			writeErr(w, 403, "cannot delete posts of this member")
			return
		}
		seq, _ = s.st.AppendEvent(ch.ID, "delete", ik, author, actor.Address, map[string]any{"post_id": in.PostID})
		s.chans.publish(ch.ID, seq)
		writeJSON(w, 200, map[string]any{"seq": seq})
		return
	}
	target, err := b64.DecodeString(in.Target)
	if err != nil || len(target) != ed25519.PublicKeySize {
		writeErr(w, 400, "invalid target")
		return
	}
	t, err := s.st.ChannelMember(ch.ID, target)
	if err != nil {
		writeErr(w, 404, "member not found")
		return
	}
	if t.Role == "owner" || (t.Role == "mod" && !isOwner) {
		writeErr(w, 403, "insufficient rights for this member")
		return
	}
	meta := map[string]any{"target_address": t.Address}
	var kind string
	switch in.Action {
	case "ban":
		kind, err = "ban", s.st.SetMember(ch.ID, target, "member", "banned", nil)
	case "unban":
		kind, err = "unban", s.st.SetMember(ch.ID, target, "member", "active", nil)
	case "kick":
		kind, err = "kick", s.st.RemoveMember(ch.ID, target)
	case "approve":
		if t.Status != "pending" {
			writeErr(w, 400, "not pending")
			return
		}
		kind, err = "approve", s.st.SetMember(ch.ID, target, "", "active", nil)
	case "reject":
		if t.Status != "pending" {
			writeErr(w, 400, "not pending")
			return
		}
		kind, err = "reject", s.st.RemoveMember(ch.ID, target)
	case "timeout":
		if in.Seconds < 0 || in.Seconds > 365*86400 {
			writeErr(w, 400, "invalid duration")
			return
		}
		until := int64(0)
		if in.Seconds > 0 {
			until = now() + in.Seconds
		}
		meta["until"] = until
		kind, err = "timeout", s.st.SetMember(ch.ID, target, "", "", &until)
	case "role":
		switch in.Role {
		case "read", "member", "write":
		case "mod":
			if !isOwner {
				writeErr(w, 403, "only the owner can appoint moderators")
				return
			}
		default:
			writeErr(w, 400, "invalid role")
			return
		}
		if t.Status != "active" {
			writeErr(w, 400, "member not active")
			return
		}
		meta["role"] = in.Role
		kind, err = "role", s.st.SetMember(ch.ID, target, in.Role, "", nil)
	default:
		writeErr(w, 400, "unknown action")
		return
	}
	if err != nil {
		writeErr(w, 500, "internal error")
		return
	}
	seq, _ = s.st.AppendEvent(ch.ID, kind, ik, target, actor.Address, meta)
	s.chans.publish(ch.ID, seq)
	writeJSON(w, 200, map[string]any{"seq": seq})
}

func (s *Server) channelMembers(w http.ResponseWriter, r *http.Request, ch *store.Channel, ik []byte, _ []byte) {
	actor, err := s.st.ChannelMember(ch.ID, ik)
	if err != nil || !actor.IsMod() {
		writeErr(w, 403, "moderator required")
		return
	}
	ms, err := s.st.ChannelMembers(ch.ID, r.URL.Query().Get("status"))
	if err != nil {
		writeErr(w, 500, "internal error")
		return
	}
	out := make([]any, 0, len(ms))
	for i := range ms {
		out = append(out, memberJSON(&ms[i], ch.Policy))
	}
	writeJSON(w, 200, map[string]any{"members": out})
}

func (s *Server) channelSettings(w http.ResponseWriter, r *http.Request, ch *store.Channel, ik []byte, body []byte) {
	var in struct {
		TitleEnc string       `json:"title_enc"`
		Policy   chanPolicyIn `json:"policy"`
	}
	if !decodeJSON(w, body, &in) {
		return
	}
	actor, err := s.st.ChannelMember(ch.ID, ik)
	if err != nil || actor.Role != "owner" {
		writeErr(w, 403, "owner required")
		return
	}
	var title []byte
	if in.TitleEnc != "" {
		if title, err = b64.DecodeString(in.TitleEnc); err != nil || len(title) > 4096 {
			writeErr(w, 400, "invalid title")
			return
		}
	}
	p := in.Policy.policy()
	if !p.Valid() {
		writeErr(w, 400, "invalid policy")
		return
	}
	if err := s.st.UpdateChannel(ch.ID, title, p); err != nil {
		writeErr(w, 500, "internal error")
		return
	}
	seq, _ := s.st.AppendEvent(ch.ID, "settings", ik, nil, actor.Address, map[string]any{"join_mode": p.JoinMode, "members_can_write": p.MembersCanWrite})
	s.chans.publish(ch.ID, seq)
	writeJSON(w, 200, map[string]any{"status": "ok"})
}

func (s *Server) channelLeave(w http.ResponseWriter, r *http.Request, ch *store.Channel, ik []byte, _ []byte) {
	m, err := s.st.ChannelMember(ch.ID, ik)
	if err != nil || m.Role == "owner" || m.Status == "banned" { // Gebannte bleiben eingetragen
		writeErr(w, 403, "forbidden")
		return
	}
	_ = s.st.RemoveMember(ch.ID, ik)
	seq, _ := s.st.AppendEvent(ch.ID, "leave", ik, ik, m.Address, nil)
	s.chans.publish(ch.ID, seq)
	writeJSON(w, 200, map[string]any{"status": "ok"})
}

func (s *Server) channelDelete(w http.ResponseWriter, r *http.Request, ch *store.Channel, ik []byte, _ []byte) {
	m, err := s.st.ChannelMember(ch.ID, ik)
	if err != nil || m.Role != "owner" {
		writeErr(w, 403, "owner required")
		return
	}
	_ = s.st.DeleteChannel(ch.ID)
	s.chans.publish(ch.ID, -1)
	writeJSON(w, 200, map[string]any{"status": "ok"})
}

// channelStream: WebSocket, meldet neue Log-Einträge ({"type":"log","seq":n}). Erste Nachricht = {ik,ts,nonce,sig} (Body-Hash "WS").
func (s *Server) channelStream(w http.ResponseWriter, r *http.Request) {
	if !s.cfg.Channels {
		writeErr(w, 404, "channels disabled")
		return
	}
	ch, err := s.st.Channel(r.PathValue("id"))
	if err != nil {
		writeErr(w, 404, "not found")
		return
	}
	c, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
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
	var a struct {
		IK    string `json:"ik"`
		Ts    string `json:"ts"`
		Nonce string `json:"nonce"`
		Sig   string `json:"sig"`
	}
	if !jsonUnmarshal(msg, &a) {
		_ = c.Close(websocket.StatusPolicyViolation, "unauthorized")
		return
	}
	ik, ok := s.chanVerify(s.cfg.Domain, "GET", r.URL.Path, map[string]string{"ik": a.IK, "ts": a.Ts, "nonce": a.Nonce, "sig": a.Sig}, "WS")
	if !ok {
		_ = c.Close(websocket.StatusPolicyViolation, "unauthorized")
		return
	}
	if m, err := s.st.ChannelMember(ch.ID, ik); err != nil || m.Status == "banned" {
		_ = c.Close(websocket.StatusPolicyViolation, "forbidden")
		return
	}
	sub := s.chans.subscribe(ch.ID)
	defer s.chans.unsubscribe(ch.ID, sub)
	ctx = c.CloseRead(r.Context())
	if writeWS(ctx, c, map[string]any{"type": "ready"}) != nil {
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
		case seq := <-sub:
			if writeWS(ctx, c, map[string]any{"type": "log", "seq": seq}) != nil {
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
