package server

import (
	"encoding/json"
	"errors"
	"net/http"
	"runtime"
	"strings"
	"time"

	"github.com/syncip/chat/server/internal/config"
	"github.com/syncip/chat/server/internal/store"
)

// Der erste registrierte Nutzer ist Administrator. Administratoren sehen Statistiken und ändern Server-Einstellungen zur Laufzeit
// (persistiert in der Datenbank, überschreibt die Umgebungsvariablen). Domain und Listen-Adresse bleiben unveränderlich.

type Settings struct {
	Registration         string   `json:"registration"` // invite | open | closed
	UserInvites          bool     `json:"user_invites"`
	RegistrationPoW      int      `json:"registration_pow"`
	MaxFileSize          int64    `json:"max_file_size"`
	MaxAttachments       int      `json:"max_message_attachments"`
	MaxMessageTotal      int64    `json:"max_message_total_size"`
	MaxMessageText       int      `json:"max_message_text"`
	MaxEnvelopeSize      int64    `json:"max_envelope_size"`
	UserQuota            int64    `json:"user_quota"`
	BlobRetentionDays    int      `json:"blob_retention_days"`
	MessageRetentionDays int      `json:"message_retention_days"`
	Federation           string   `json:"federation"` // open | allowlist | closed
	FedAllow             []string `json:"federation_allow"`
	FedBlock             []string `json:"federation_block"`
	RatePerMinute        int      `json:"rate_per_minute"`
	MaxMailboxes         int      `json:"max_mailboxes"`
	MaxDevices           int      `json:"max_devices"`
	Channels             bool     `json:"channels"`
	MaxChannels          int      `json:"max_channels"`
	MaxChannelMembers    int      `json:"max_channel_members"`
	MaxPostSize          int      `json:"max_post_size"`
	ChannelRetentionDays int      `json:"channel_retention_days"`
	MaxHooks             int      `json:"max_hooks"`
}

func settingsOf(c *config.Config) Settings {
	days := func(d time.Duration) int { return int(d.Hours() / 24) }
	return Settings{
		Registration: c.Registration, UserInvites: c.UserInvites, RegistrationPoW: c.RegistrationPoW, MaxFileSize: c.MaxFileSize,
		MaxAttachments: c.MaxAttachments, MaxMessageTotal: c.MaxMessageTotal, MaxMessageText: c.MaxMessageText, MaxEnvelopeSize: c.MaxEnvelopeSize,
		UserQuota: c.UserQuota, BlobRetentionDays: days(c.BlobRetention), MessageRetentionDays: days(c.MessageRetention),
		Federation: c.Federation, FedAllow: append([]string{}, c.FedAllow...), FedBlock: append([]string{}, c.FedBlock...),
		RatePerMinute: c.RatePerMinute, MaxMailboxes: c.MaxMailboxes, MaxDevices: c.MaxDevices, Channels: c.Channels, MaxChannels: c.MaxChannels,
		MaxChannelMembers: c.MaxChannelUsers, MaxPostSize: c.MaxPostSize, ChannelRetentionDays: days(c.ChannelRetention), MaxHooks: c.MaxHooks,
	}
}

func (st Settings) validate() error {
	switch st.Registration {
	case "invite", "open", "closed":
	default:
		return errors.New("registration: invite|open|closed")
	}
	switch st.Federation {
	case "open", "allowlist", "closed":
	default:
		return errors.New("federation: open|allowlist|closed")
	}
	pos := map[string]int64{
		"max_file_size": st.MaxFileSize, "max_message_attachments": int64(st.MaxAttachments), "max_message_total_size": st.MaxMessageTotal,
		"max_message_text": int64(st.MaxMessageText), "max_envelope_size": st.MaxEnvelopeSize, "user_quota": st.UserQuota,
		"blob_retention_days": int64(st.BlobRetentionDays), "message_retention_days": int64(st.MessageRetentionDays),
		"rate_per_minute": int64(st.RatePerMinute), "max_mailboxes": int64(st.MaxMailboxes), "max_devices": int64(st.MaxDevices),
		"max_channels": int64(st.MaxChannels), "max_channel_members": int64(st.MaxChannelMembers), "max_post_size": int64(st.MaxPostSize),
		"channel_retention_days": int64(st.ChannelRetentionDays), "max_hooks": int64(st.MaxHooks),
	}
	for k, v := range pos {
		if v < 1 {
			return errors.New(k + " muss mindestens 1 sein")
		}
	}
	if st.RegistrationPoW < 0 || st.RegistrationPoW > 28 {
		return errors.New("registration_pow: 0–28")
	}
	return nil
}

func (st Settings) applyTo(c config.Config) *config.Config {
	hours := func(d int) time.Duration { return time.Duration(d) * 24 * time.Hour }
	norm := func(l []string) []string {
		var out []string
		for _, x := range l {
			if x = strings.ToLower(strings.TrimSpace(x)); x != "" {
				out = append(out, x)
			}
		}
		return out
	}
	c.Registration, c.UserInvites, c.RegistrationPoW, c.MaxFileSize = st.Registration, st.UserInvites, st.RegistrationPoW, st.MaxFileSize
	c.MaxAttachments, c.MaxMessageTotal, c.MaxMessageText, c.MaxEnvelopeSize = st.MaxAttachments, st.MaxMessageTotal, st.MaxMessageText, st.MaxEnvelopeSize
	c.UserQuota, c.BlobRetention, c.MessageRetention = st.UserQuota, hours(st.BlobRetentionDays), hours(st.MessageRetentionDays)
	c.Federation, c.FedAllow, c.FedBlock = st.Federation, norm(st.FedAllow), norm(st.FedBlock)
	c.RatePerMinute, c.MaxMailboxes, c.MaxDevices, c.Channels = st.RatePerMinute, st.MaxMailboxes, st.MaxDevices, st.Channels
	c.MaxChannels, c.MaxChannelUsers, c.MaxPostSize, c.ChannelRetention, c.MaxHooks = st.MaxChannels, st.MaxChannelMembers, st.MaxPostSize, hours(st.ChannelRetentionDays), st.MaxHooks
	return &c
}

// loadSettings übernimmt die vom Administrator gespeicherten Einstellungen (falls vorhanden) über die Umgebungsvariablen.
func (s *Server) loadSettings() error {
	raw, err := s.st.Meta("settings")
	if errors.Is(err, store.ErrNotFound) {
		return nil
	}
	if err != nil {
		return err
	}
	var st Settings
	if json.Unmarshal(raw, &st) != nil || st.validate() != nil {
		s.log.Warn("gespeicherte Server-Einstellungen ungültig, ignoriert")
		return nil
	}
	s.cfgp.Store(st.applyTo(*s.conf()))
	s.limiter.setRate(st.RatePerMinute)
	return nil
}

func (s *Server) adminOnly(h userHandler) http.HandlerFunc {
	return s.auth(func(w http.ResponseWriter, r *http.Request, u *store.User) {
		if !u.Admin {
			writeErr(w, 403, "admin only")
			return
		}
		h(w, r, u)
	})
}

func (s *Server) me(w http.ResponseWriter, r *http.Request, u *store.User) {
	writeJSON(w, 200, map[string]any{"name": u.Name, "admin": u.Admin})
}

func (s *Server) adminStats(w http.ResponseWriter, r *http.Request, u *store.User) {
	st, err := s.st.Stats(s.conf().DataDir)
	if err != nil {
		writeErr(w, 500, "internal error")
		return
	}
	var ms runtime.MemStats
	runtime.ReadMemStats(&ms)
	writeJSON(w, 200, map[string]any{
		"stats": st, "uptime_seconds": int64(time.Since(s.started).Seconds()), "requests_total": s.reqTotal.Load(),
		"ws_connections": s.hub.count(), "goroutines": runtime.NumGoroutine(), "memory_bytes": ms.Alloc, "version": apiVersion,
		"domain": s.conf().Domain,
	})
}

func (s *Server) adminGetSettings(w http.ResponseWriter, r *http.Request, u *store.User) {
	writeJSON(w, 200, settingsOf(s.conf()))
}

func (s *Server) adminPutSettings(w http.ResponseWriter, r *http.Request, u *store.User) {
	var in Settings
	if !readJSON(w, r, 64<<10, &in) {
		return
	}
	if err := in.validate(); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	raw, _ := json.Marshal(in)
	if err := s.st.SetMeta("settings", raw); err != nil {
		writeErr(w, 500, "internal error")
		return
	}
	s.cfgp.Store(in.applyTo(*s.conf()))
	s.limiter.setRate(in.RatePerMinute)
	writeJSON(w, 200, settingsOf(s.conf()))
}

func (s *Server) adminUsers(w http.ResponseWriter, r *http.Request, u *store.User) {
	us, err := s.st.ListUsers(r.URL.Query().Get("q"), 0)
	if err != nil {
		writeErr(w, 500, "internal error")
		return
	}
	out := make([]map[string]any, 0, len(us))
	for _, x := range us {
		out = append(out, map[string]any{"name": x.Name, "admin": x.Admin, "created_at": x.CreatedAt, "devices": x.Devices, "blob_bytes": x.BlobBytes, "channels": x.Channels,
			"banned_until": x.BannedUntil, "ban_reason": x.BanReason, "rate_limit": x.RateLimit, "rate_until": x.RateUntil})
	}
	writeJSON(w, 200, map[string]any{"users": out})
}

func (s *Server) adminSetAdmin(w http.ResponseWriter, r *http.Request, u *store.User) {
	var in struct {
		Admin bool `json:"admin"`
	}
	if !readJSON(w, r, 1<<10, &in) {
		return
	}
	switch err := s.st.SetAdmin(r.PathValue("name"), in.Admin); {
	case errors.Is(err, store.ErrNotFound):
		writeErr(w, 404, "not found")
	case errors.Is(err, store.ErrLimit):
		writeErr(w, 409, "the last administrator cannot be removed")
	case err != nil:
		writeErr(w, 500, "internal error")
	default:
		writeJSON(w, 200, map[string]any{"status": "ok"})
	}
}

// adminRestrict sperrt einen Nutzer oder begrenzt dessen Anfragerate.
// ban: "" (keine) | "perm" | "temp" (mit ban_minutes). rate_limit: Anfragen/Minute (0 = Standard), rate_minutes: Dauer (0 = unbefristet).
func (s *Server) adminRestrict(w http.ResponseWriter, r *http.Request, u *store.User) {
	var in struct {
		Ban         string `json:"ban"`
		BanMinutes  int64  `json:"ban_minutes"`
		Reason      string `json:"reason"`
		RateLimit   int    `json:"rate_limit"`
		RateMinutes int64  `json:"rate_minutes"`
	}
	if !readJSON(w, r, 1<<10, &in) {
		return
	}
	name := r.PathValue("name")
	if name == u.Name {
		writeErr(w, 409, "you cannot restrict yourself")
		return
	}
	const maxMin = 10 * 365 * 24 * 60
	if in.RateLimit < 0 || in.RateLimit > 100000 || in.BanMinutes < 0 || in.BanMinutes > maxMin || in.RateMinutes < 0 || in.RateMinutes > maxMin || len(in.Reason) > 300 {
		writeErr(w, 400, "invalid parameters")
		return
	}
	var until, rateUntil int64
	switch in.Ban {
	case "":
	case "perm":
		until = -1
	case "temp":
		if in.BanMinutes < 1 {
			writeErr(w, 400, "ban_minutes required")
			return
		}
		until = time.Now().Add(time.Duration(in.BanMinutes) * time.Minute).Unix()
	default:
		writeErr(w, 400, "invalid ban")
		return
	}
	if in.RateLimit > 0 && in.RateMinutes > 0 {
		rateUntil = time.Now().Add(time.Duration(in.RateMinutes) * time.Minute).Unix()
	}
	switch err := s.st.SetRestriction(name, until, in.Reason, in.RateLimit, rateUntil); {
	case errors.Is(err, store.ErrNotFound):
		writeErr(w, 404, "not found")
	case errors.Is(err, store.ErrLimit):
		writeErr(w, 409, "administrators cannot be banned")
	case err != nil:
		writeErr(w, 500, "internal error")
	default:
		s.userLim.reset(name)
		writeJSON(w, 200, map[string]any{"status": "ok"})
	}
}
