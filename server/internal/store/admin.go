package store

import (
	"database/sql"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// ---- Administration ----

type UserRow struct {
	Name      string
	Admin     bool
	CreatedAt int64
	Devices   int
	BlobBytes int64
	Channels  int

	BannedUntil int64
	BanReason   string
	RateLimit   int
	RateUntil   int64
}

// ListUsers: q filtert per Teilstring im Namen (case-insensitive), limit ≤ 500.
func (s *Store) ListUsers(q string, limit int) ([]UserRow, error) {
	if limit <= 0 || limit > 500 {
		limit = 500
	}
	esc := strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`).Replace(strings.ToLower(q))
	rows, err := s.db.Query(`SELECT u.name,u.is_admin,u.created_at,
 (SELECT COUNT(*) FROM devices d WHERE d.user_id=u.id),
 COALESCE((SELECT SUM(size) FROM blobs b WHERE b.user_id=u.id),0),
 (SELECT COUNT(*) FROM channels c WHERE c.owner_user=u.id),
 u.banned_until,u.ban_reason,u.rate_limit,u.rate_until
 FROM users u WHERE lower(u.name) LIKE ? ESCAPE '\' ORDER BY u.id LIMIT ?`, "%"+esc+"%", limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []UserRow
	for rows.Next() {
		var r UserRow
		var adm int
		if err := rows.Scan(&r.Name, &adm, &r.CreatedAt, &r.Devices, &r.BlobBytes, &r.Channels, &r.BannedUntil, &r.BanReason, &r.RateLimit, &r.RateUntil); err != nil {
			return nil, err
		}
		r.Admin = adm == 1
		out = append(out, r)
	}
	return out, rows.Err()
}

func (s *Store) SetAdmin(name string, admin bool) error {
	v := 0
	if admin {
		v = 1
	}
	if !admin { // der letzte Administrator bleibt
		var n int
		_ = s.db.QueryRow(`SELECT COUNT(*) FROM users WHERE is_admin=1 AND name<>?`, name).Scan(&n)
		if n == 0 {
			return ErrLimit
		}
	}
	res, err := s.db.Exec(`UPDATE users SET is_admin=? WHERE name=?`, v, name)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

type Stats struct {
	Users          int   `json:"users"`
	Admins         int   `json:"admins"`
	NewUsers24h    int   `json:"new_users_24h"`
	Devices        int   `json:"devices"`
	Mailboxes      int   `json:"mailboxes"`
	QueuedMessages int   `json:"queued_messages"`
	QueuedBytes    int64 `json:"queued_bytes"`
	Blobs          int   `json:"blobs"`
	BlobBytes      int64 `json:"blob_bytes"`
	Channels       int   `json:"channels"`
	PublicChannels int   `json:"public_channels"`
	ChannelMembers int   `json:"channel_members"`
	ChannelPosts   int   `json:"channel_posts"`
	Posts24h       int   `json:"channel_posts_24h"`
	Hooks          int   `json:"webhooks"`
	DBBytes        int64 `json:"db_bytes"`
	PendingInvites int   `json:"open_invites"`
}

func (s *Store) Stats(dir string) (Stats, error) {
	var st Stats
	q := func(dst any, sql string, args ...any) {
		_ = s.db.QueryRow(sql, args...).Scan(dst)
	}
	q(&st.Users, `SELECT COUNT(*) FROM users`)
	q(&st.Admins, `SELECT COUNT(*) FROM users WHERE is_admin=1`)
	q(&st.NewUsers24h, `SELECT COUNT(*) FROM users WHERE created_at>?`, time.Now().Add(-24*time.Hour).Unix())
	q(&st.Devices, `SELECT COUNT(*) FROM devices`)
	q(&st.Mailboxes, `SELECT COUNT(*) FROM mailboxes`)
	q(&st.QueuedMessages, `SELECT COUNT(*) FROM messages`)
	q(&st.QueuedBytes, `SELECT COALESCE(SUM(LENGTH(data)),0) FROM messages`)
	q(&st.Blobs, `SELECT COUNT(*) FROM blobs`)
	q(&st.BlobBytes, `SELECT COALESCE(SUM(size),0) FROM blobs`)
	q(&st.Channels, `SELECT COUNT(*) FROM channels`)
	q(&st.PublicChannels, `SELECT COUNT(*) FROM channels WHERE policy LIKE '%"public":true%'`)
	q(&st.ChannelMembers, `SELECT COUNT(*) FROM channel_members WHERE status='active'`)
	q(&st.ChannelPosts, `SELECT COUNT(*) FROM channel_log WHERE type='post'`)
	q(&st.Posts24h, `SELECT COUNT(*) FROM channel_log WHERE type='post' AND ts>?`, time.Now().Add(-24*time.Hour).UnixMilli())
	q(&st.Hooks, `SELECT COUNT(*) FROM channel_hooks`)
	q(&st.PendingInvites, `SELECT COUNT(*) FROM invites WHERE uses_left>0 AND expires_at>?`, now())
	for _, f := range []string{"chat.db", "chat.db-wal"} {
		if fi, err := os.Stat(filepath.Join(dir, f)); err == nil {
			st.DBBytes += fi.Size()
		}
	}
	return st, nil
}

// ---- Konto-Sync (verschlüsselter Blob, der Server sieht nur Chiffretext) ----

func (s *Store) GetSync(uid int64) (version int64, data []byte, err error) {
	err = s.db.QueryRow(`SELECT version,data FROM account_sync WHERE user_id=?`, uid).Scan(&version, &data)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, nil, nil
	}
	return
}

// PutSync speichert, wenn base der aktuellen Version entspricht (Compare-and-Swap). ok=false: Konflikt (cur = aktuelle Version).
func (s *Store) PutSync(uid, base int64, data []byte) (newVersion int64, ok bool, err error) {
	tx, err := s.db.Begin()
	if err != nil {
		return 0, false, err
	}
	defer tx.Rollback()
	var cur int64
	err = tx.QueryRow(`SELECT version FROM account_sync WHERE user_id=?`, uid).Scan(&cur)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return 0, false, err
	}
	if cur != base {
		return cur, false, nil
	}
	cur++
	if _, err := tx.Exec(`INSERT INTO account_sync(user_id,version,data,updated_at) VALUES(?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET version=excluded.version, data=excluded.data, updated_at=excluded.updated_at`, uid, cur, data, now()); err != nil {
		return 0, false, err
	}
	return cur, true, tx.Commit()
}

// ---- Webhooks ----

const hookSchema = `
CREATE TABLE IF NOT EXISTS channel_hooks(
  id TEXT PRIMARY KEY,
  channel_id TEXT NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  token_hash BLOB NOT NULL UNIQUE,
  key BLOB,
  created_at INTEGER NOT NULL,
  last_used INTEGER NOT NULL DEFAULT 0
);
`

type Hook struct {
	ID        string
	ChannelID string
	Name      string
	Key       []byte // Kanalschlüssel (nur bei nicht-öffentlichen Kanälen; der Server muss Webhook-Beiträge selbst verschlüsseln)
	CreatedAt int64
	LastUsed  int64
}

func (s *Store) CreateHook(h Hook, tokenHash []byte, maxPerChannel int) error {
	var n int
	_ = s.db.QueryRow(`SELECT COUNT(*) FROM channel_hooks WHERE channel_id=?`, h.ChannelID).Scan(&n)
	if n >= maxPerChannel {
		return ErrLimit
	}
	_, err := s.db.Exec(`INSERT INTO channel_hooks(id,channel_id,name,token_hash,key,created_at) VALUES(?,?,?,?,?,?)`, h.ID, h.ChannelID, h.Name, tokenHash, h.Key, now())
	if err != nil {
		return ErrConflict
	}
	return nil
}

func (s *Store) ListHooks(chID string) ([]Hook, error) {
	rows, err := s.db.Query(`SELECT id,name,created_at,last_used FROM channel_hooks WHERE channel_id=? ORDER BY created_at`, chID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Hook
	for rows.Next() {
		h := Hook{ChannelID: chID}
		if err := rows.Scan(&h.ID, &h.Name, &h.CreatedAt, &h.LastUsed); err != nil {
			return nil, err
		}
		out = append(out, h)
	}
	return out, rows.Err()
}

func (s *Store) DeleteHook(chID, id string) error {
	res, err := s.db.Exec(`DELETE FROM channel_hooks WHERE channel_id=? AND id=?`, chID, id)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *Store) HookByToken(tokenHash []byte) (*Hook, error) {
	h := &Hook{}
	err := s.db.QueryRow(`SELECT id,channel_id,name,key,created_at FROM channel_hooks WHERE token_hash=?`, tokenHash).Scan(&h.ID, &h.ChannelID, &h.Name, &h.Key, &h.CreatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return h, err
}

func (s *Store) TouchHook(id string) {
	_, _ = s.db.Exec(`UPDATE channel_hooks SET last_used=? WHERE id=?`, now(), id)
}

// AppendHookPost fügt einen über einen Webhook eingegangenen (vom Server verschlüsselten bzw. öffentlichen) Beitrag an.
func (s *Store) AppendHookPost(chID, postID, hookName string, tsMillis int64, data []byte) (int64, error) {
	res, err := s.db.Exec(`INSERT INTO channel_log(channel_id,type,post_id,address,ts,epoch,data,hook) VALUES(?,'post',?,?,?,0,?,?)`,
		chID, postID, "🔔 "+hookName, tsMillis, data, hookName)
	if err != nil {
		return 0, ErrConflict
	}
	return res.LastInsertId()
}

// SetRestriction setzt Sperre und Anfragelimit. bannedUntil: 0 = keine, -1 = dauerhaft, sonst Unix-Ende.
// Administratoren können nicht gesperrt werden.
func (s *Store) SetRestriction(name string, bannedUntil int64, reason string, rate int, rateUntil int64) error {
	var adm int
	if err := s.db.QueryRow(`SELECT is_admin FROM users WHERE name=?`, name).Scan(&adm); errors.Is(err, sql.ErrNoRows) {
		return ErrNotFound
	} else if err != nil {
		return err
	}
	if adm == 1 && bannedUntil != 0 {
		return ErrLimit
	}
	_, err := s.db.Exec(`UPDATE users SET banned_until=?,ban_reason=?,rate_limit=?,rate_until=? WHERE name=?`,
		bannedUntil, reason, rate, rateUntil, name)
	return err
}
