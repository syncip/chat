package server

import (
	"crypto/sha256"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/syncip/chat/server/internal/store"
)

// Webhooks für Kanäle, kompatibel zur Publish-API von ntfy (https://docs.ntfy.sh/publish/):
//   POST/PUT  /h/{token}            Body = Nachricht (oder JSON {message,title,priority,tags,click})
//   GET       /h/{token}/publish    ?message=…&title=…&priority=…&tags=…&click=…   (auch /send und /trigger)
// Titel/Priorität/Tags/Click per Header (Title, Priority, Tags, Click; Kurzformen t, p, ta; X-Präfix) oder Query.
// Das Token ist das Geheimnis; der Server speichert nur seinen Hash.

func hashTokenStr(t string) []byte { h := sha256.Sum256([]byte("hook:" + t)); return h[:] }

func (s *Server) channelHookCreate(w http.ResponseWriter, r *http.Request, ch *store.Channel, ik []byte, body []byte) {
	actor, err := s.st.ChannelMember(ch.ID, ik)
	if err != nil || !actor.IsMod() {
		writeErr(w, 403, "moderator required")
		return
	}
	var in struct {
		Name string `json:"name"`
		Key  string `json:"key"`
	}
	if !decodeJSON(w, body, &in) {
		return
	}
	in.Name = strings.TrimSpace(in.Name)
	if in.Name == "" || len([]rune(in.Name)) > 40 {
		writeErr(w, 400, "invalid name")
		return
	}
	var key []byte
	if !ch.Policy.Public {
		key, err = b64.DecodeString(in.Key)
		if err != nil || len(key) != 32 {
			writeErr(w, 400, "key required for non-public channels")
			return
		}
		// Schlüssel prüfen: er muss den Kanaltitel entschlüsseln
		if k, g, _, err := openEnvelope(key, ch.TitleEnc); err != nil || k != kindChannel || string(g) != "title" {
			writeErr(w, 400, "wrong channel key")
			return
		}
	}
	token := randID(24)
	id := randID(8)
	max := s.conf().MaxHooks
	if max <= 0 {
		max = 5
	}
	switch err := s.st.CreateHook(store.Hook{ID: id, ChannelID: ch.ID, Name: in.Name, Key: key}, hashTokenStr(token), max); {
	case errors.Is(err, store.ErrLimit):
		writeErr(w, 429, "hook limit reached")
		return
	case err != nil:
		writeErr(w, 500, "internal error")
		return
	}
	seq, _ := s.st.AppendEvent(ch.ID, "hook_add", ik, nil, actor.Address, map[string]any{"name": in.Name})
	s.chans.publish(ch.ID, seq)
	writeJSON(w, 201, map[string]any{"id": id, "name": in.Name, "token": token, "path": "/h/" + token})
}

func (s *Server) channelHookList(w http.ResponseWriter, r *http.Request, ch *store.Channel, ik []byte, _ []byte) {
	actor, err := s.st.ChannelMember(ch.ID, ik)
	if err != nil || !actor.IsMod() {
		writeErr(w, 403, "moderator required")
		return
	}
	hs, err := s.st.ListHooks(ch.ID)
	if err != nil {
		writeErr(w, 500, "internal error")
		return
	}
	out := make([]any, 0, len(hs))
	for _, h := range hs {
		out = append(out, map[string]any{"id": h.ID, "name": h.Name, "created_at": h.CreatedAt, "last_used": h.LastUsed})
	}
	writeJSON(w, 200, map[string]any{"hooks": out})
}

func (s *Server) channelHookDelete(w http.ResponseWriter, r *http.Request, ch *store.Channel, ik []byte, _ []byte) {
	actor, err := s.st.ChannelMember(ch.ID, ik)
	if err != nil || !actor.IsMod() {
		writeErr(w, 403, "moderator required")
		return
	}
	if err := s.st.DeleteHook(ch.ID, r.PathValue("hid")); err != nil {
		writeErr(w, 404, "not found")
		return
	}
	seq, _ := s.st.AppendEvent(ch.ID, "hook_remove", ik, nil, actor.Address, map[string]any{"id": r.PathValue("hid")})
	s.chans.publish(ch.ID, seq)
	writeJSON(w, 200, map[string]any{"status": "ok"})
}

// ---- ntfy-kompatibles Veröffentlichen ----

var tagEmoji = map[string]string{
	"warning": "⚠️", "skull": "💀", "white_check_mark": "✅", "heavy_check_mark": "✔️", "x": "❌", "tada": "🎉", "rotating_light": "🚨",
	"+1": "👍", "-1": "👎", "fire": "🔥", "bell": "🔔", "mag": "🔍", "computer": "💻", "package": "📦", "rocket": "🚀", "bug": "🐛",
	"green_circle": "🟢", "red_circle": "🔴", "yellow_circle": "🟡", "information_source": "ℹ️", "lock": "🔒", "key": "🔑", "email": "📧",
	"house": "🏠", "zap": "⚡", "thermometer": "🌡️", "droplet": "💧", "battery": "🔋", "stop_sign": "🛑", "sos": "🆘", "partying_face": "🥳",
}

type ntfyMsg struct {
	Message  string
	Title    string
	Priority int
	Tags     []string
	Click    string
}

func firstOf(vals ...string) string {
	for _, v := range vals {
		if v != "" {
			return v
		}
	}
	return ""
}

func parsePriority(v string) int {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "", "default", "3":
		return 3
	case "min", "1":
		return 1
	case "low", "2":
		return 2
	case "high", "4":
		return 4
	case "max", "urgent", "5":
		return 5
	}
	return 3
}

func splitTags(v string) []string {
	var out []string
	for _, t := range strings.Split(v, ",") {
		if t = strings.TrimSpace(t); t != "" {
			out = append(out, t)
		}
	}
	return out
}

func parseNtfy(r *http.Request) (ntfyMsg, error) {
	q := r.URL.Query()
	h := r.Header
	pick := func(names ...string) string {
		for _, n := range names {
			if v := h.Get(n); v != "" {
				return v
			}
			if v := q.Get(strings.ToLower(strings.TrimPrefix(n, "X-"))); v != "" {
				return v
			}
		}
		return ""
	}
	m := ntfyMsg{
		Title:    firstOf(pick("Title", "X-Title"), q.Get("t")),
		Priority: parsePriority(firstOf(pick("Priority", "X-Priority"), q.Get("p"))),
		Tags:     splitTags(firstOf(pick("Tags", "X-Tags"), q.Get("ta"))),
		Click:    firstOf(pick("Click", "X-Click")),
		Message:  firstOf(q.Get("message"), q.Get("m")),
	}
	if r.Method == http.MethodPost || r.Method == http.MethodPut {
		raw, err := io.ReadAll(http.MaxBytesReader(nil, r.Body, 8<<10))
		if err != nil {
			return m, errors.New("body too large")
		}
		if strings.HasPrefix(h.Get("Content-Type"), "application/json") && len(raw) > 0 {
			var j struct {
				Message  string          `json:"message"`
				Title    string          `json:"title"`
				Priority json.RawMessage `json:"priority"`
				Tags     []string        `json:"tags"`
				Click    string          `json:"click"`
			}
			if err := json.Unmarshal(raw, &j); err != nil {
				return m, errors.New("invalid json")
			}
			m.Message = firstOf(j.Message, m.Message)
			m.Title = firstOf(j.Title, m.Title)
			if len(j.Tags) > 0 {
				m.Tags = j.Tags
			}
			m.Click = firstOf(j.Click, m.Click)
			if len(j.Priority) > 0 {
				m.Priority = parsePriority(strings.Trim(string(j.Priority), `"`))
			}
		} else if len(raw) > 0 {
			m.Message = string(raw)
		}
	}
	if len(m.Message) > 4096 || len(m.Title) > 250 || len(m.Click) > 2000 {
		return m, errors.New("message too large")
	}
	if strings.TrimSpace(m.Message) == "" && strings.TrimSpace(m.Title) == "" {
		return m, errors.New("empty message")
	}
	if m.Message == "" {
		m.Message = "triggered"
	}
	return m, nil
}

// render wandelt eine ntfy-Nachricht in Markdown-Text des Chats (Titel fett, Tags als Emoji bzw. #tag, Priorität als Präfix).
func (m ntfyMsg) render() string {
	var emoji, rest []string
	for _, t := range m.Tags {
		if e, ok := tagEmoji[strings.ToLower(t)]; ok {
			emoji = append(emoji, e)
		} else {
			rest = append(rest, "#"+strings.ReplaceAll(t, " ", "_"))
		}
	}
	prefix := strings.Join(emoji, "")
	switch m.Priority {
	case 5:
		prefix = "🚨" + prefix
	case 4:
		prefix = "❗" + prefix
	}
	var b strings.Builder
	if m.Title != "" {
		if prefix != "" {
			b.WriteString(prefix + " ")
		}
		b.WriteString("**" + strings.ReplaceAll(m.Title, "*", "") + "**\n")
	} else if prefix != "" {
		b.WriteString(prefix + " ")
	}
	b.WriteString(m.Message)
	if len(rest) > 0 {
		b.WriteString("\n" + strings.Join(rest, " "))
	}
	if strings.HasPrefix(m.Click, "https://") {
		b.WriteString("\n" + m.Click)
	}
	return b.String()
}

func (s *Server) hookPublish(w http.ResponseWriter, r *http.Request) {
	if !s.conf().Channels {
		writeErr(w, 404, "channels disabled")
		return
	}
	h, err := s.st.HookByToken(hashTokenStr(r.PathValue("token")))
	if err != nil {
		writeErr(w, 404, "not found")
		return
	}
	ch, err := s.st.Channel(h.ChannelID)
	if err != nil {
		writeErr(w, 404, "not found")
		return
	}
	m, perr := parseNtfy(r)
	if perr != nil {
		writeErr(w, 400, perr.Error())
		return
	}
	payload, _ := json.Marshal(map[string]any{"v": 1, "parts": []map[string]string{{"type": "text", "body": m.render()}}})
	data := payload
	if !ch.Policy.Public {
		if len(h.Key) != 32 {
			writeErr(w, 500, "hook has no key")
			return
		}
		if data, err = sealEnvelope(h.Key, kindChannel, []byte(ch.ID), payload); err != nil {
			writeErr(w, 500, "internal error")
			return
		}
	}
	ts := time.Now().UnixMilli()
	postID := randID(10)
	seq, err := s.st.AppendHookPost(ch.ID, postID, h.Name, ts, data)
	if err != nil {
		writeErr(w, 500, "internal error")
		return
	}
	s.st.TouchHook(h.ID)
	s.chans.publish(ch.ID, seq)
	writeJSON(w, 200, map[string]any{"id": postID, "time": ts / 1000, "event": "message", "topic": ch.ID, "message": m.Message, "title": m.Title, "priority": m.Priority, "tags": m.Tags})
}
