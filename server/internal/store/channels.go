package store

import (
	"database/sql"
	"encoding/json"
	"errors"
	"time"
)

// Öffentliche Kanäle (siehe docs/CHANNELS.md). Der Server speichert nur Chiffretext und Zustelldaten; Mitglieder werden über ihren
// Konto-Schlüssel (AIK) identifiziert. Rechte und Sperren werden vom Server erzwungen.

const channelSchema = `
CREATE TABLE IF NOT EXISTS channels(
  id TEXT PRIMARY KEY,
  owner_user INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  owner_ik BLOB NOT NULL,
  title_enc BLOB NOT NULL,
  policy TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS channel_members(
  channel_id TEXT NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
  ik BLOB NOT NULL,
  address TEXT NOT NULL,
  role TEXT NOT NULL DEFAULT 'member',      -- owner | mod | write | member | read
  status TEXT NOT NULL DEFAULT 'active',    -- active | pending | banned
  joined_at INTEGER NOT NULL,
  muted_until INTEGER NOT NULL DEFAULT 0,
  last_post_at INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(channel_id, ik)
);
CREATE TABLE IF NOT EXISTS channel_log(
  seq INTEGER PRIMARY KEY AUTOINCREMENT,
  channel_id TEXT NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
  type TEXT NOT NULL,                       -- post | event
  post_id TEXT,
  ik BLOB,
  address TEXT,
  ts INTEGER NOT NULL,
  epoch INTEGER NOT NULL DEFAULT 0,
  data BLOB,
  sig BLOB,
  deleted INTEGER NOT NULL DEFAULT 0,
  kind TEXT,
  target BLOB,
  meta TEXT
);
CREATE INDEX IF NOT EXISTS clog_ch ON channel_log(channel_id, seq);
CREATE UNIQUE INDEX IF NOT EXISTS clog_post ON channel_log(channel_id, post_id) WHERE post_id IS NOT NULL;
`

var (
	ErrForbidden = errors.New("forbidden")
	ErrBanned    = errors.New("banned")
)

type Channel struct {
	ID        string
	OwnerUser int64
	OwnerIK   []byte
	TitleEnc  []byte
	Policy    ChannelPolicy
	CreatedAt int64
}

// ChannelPolicy: globale Einstellungen (vom Besitzer änderbar).
type ChannelPolicy struct {
	JoinMode         string `json:"join_mode"`         // open | approval | pow | captcha
	PowBits          int    `json:"pow_bits"`          // bei pow
	ProbationSeconds int64  `json:"probation_seconds"` // Neue dürfen erst nach dieser Zeit schreiben
	MembersCanWrite  bool   `json:"members_can_write"` // false: Kanal ist für normale Mitglieder „nur lesen“
	SlowModeSeconds  int64  `json:"slow_mode_seconds"` // Mindestabstand zwischen Beiträgen eines Mitglieds
}

func (p ChannelPolicy) Valid() bool {
	switch p.JoinMode {
	case "open", "approval", "pow", "captcha":
	default:
		return false
	}
	return p.PowBits >= 0 && p.PowBits <= 28 && p.ProbationSeconds >= 0 && p.ProbationSeconds <= 365*86400 && p.SlowModeSeconds >= 0 && p.SlowModeSeconds <= 86400
}

type ChannelMember struct {
	ChannelID  string
	IK         []byte
	Address    string
	Role       string
	Status     string
	JoinedAt   int64
	MutedUntil int64
	LastPostAt int64
}

// CanWrite wertet globale Regel, individuelle Rolle, Timeout und Sperrfrist aus.
func (m ChannelMember) CanWrite(p ChannelPolicy, nowUnix int64) bool {
	if m.Status != "active" {
		return false
	}
	switch m.Role {
	case "owner", "mod":
		return true
	case "read":
		return false
	}
	if m.MutedUntil > nowUnix {
		return false
	}
	if m.Role == "write" { // ausdrücklich freigeschaltet: auch bei globalem „nur lesen“ und während der Sperrfrist
		return true
	}
	if !p.MembersCanWrite {
		return false
	}
	return nowUnix >= m.JoinedAt+p.ProbationSeconds
}

func (m ChannelMember) IsMod() bool {
	return m.Status == "active" && (m.Role == "owner" || m.Role == "mod")
}

type LogEntry struct {
	Seq     int64
	Type    string
	PostID  string
	IK      []byte
	Address string
	TS      int64
	Epoch   int
	Data    []byte
	Sig     []byte
	Deleted bool
	Kind    string
	Target  []byte
	Meta    string
}

func (s *Store) CreateChannel(c Channel, ownerAddr string, maxPerUser int) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var n int
	if err := tx.QueryRow(`SELECT COUNT(*) FROM channels WHERE owner_user=?`, c.OwnerUser).Scan(&n); err != nil {
		return err
	}
	if n >= maxPerUser {
		return ErrLimit
	}
	pj, _ := json.Marshal(c.Policy)
	if _, err := tx.Exec(`INSERT INTO channels(id,owner_user,owner_ik,title_enc,policy,created_at) VALUES(?,?,?,?,?,?)`,
		c.ID, c.OwnerUser, c.OwnerIK, c.TitleEnc, string(pj), now()); err != nil {
		return ErrConflict
	}
	if _, err := tx.Exec(`INSERT INTO channel_members(channel_id,ik,address,role,status,joined_at) VALUES(?,?,?,'owner','active',?)`,
		c.ID, c.OwnerIK, ownerAddr, now()); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *Store) Channel(id string) (*Channel, error) {
	c := &Channel{}
	var pj string
	err := s.db.QueryRow(`SELECT id,owner_user,owner_ik,title_enc,policy,created_at FROM channels WHERE id=?`, id).
		Scan(&c.ID, &c.OwnerUser, &c.OwnerIK, &c.TitleEnc, &pj, &c.CreatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	_ = json.Unmarshal([]byte(pj), &c.Policy)
	return c, nil
}

func (s *Store) UpdateChannel(id string, titleEnc []byte, p ChannelPolicy) error {
	pj, _ := json.Marshal(p)
	if titleEnc != nil {
		_, err := s.db.Exec(`UPDATE channels SET title_enc=?, policy=? WHERE id=?`, titleEnc, string(pj), id)
		return err
	}
	_, err := s.db.Exec(`UPDATE channels SET policy=? WHERE id=?`, string(pj), id)
	return err
}

func (s *Store) DeleteChannel(id string) error {
	_, err := s.db.Exec(`DELETE FROM channels WHERE id=?`, id)
	return err
}

func (s *Store) ChannelMember(chID string, ik []byte) (*ChannelMember, error) {
	m := &ChannelMember{ChannelID: chID}
	err := s.db.QueryRow(`SELECT ik,address,role,status,joined_at,muted_until,last_post_at FROM channel_members WHERE channel_id=? AND ik=?`, chID, ik).
		Scan(&m.IK, &m.Address, &m.Role, &m.Status, &m.JoinedAt, &m.MutedUntil, &m.LastPostAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return m, err
}

func (s *Store) ChannelMembers(chID string, status string) ([]ChannelMember, error) {
	q := `SELECT ik,address,role,status,joined_at,muted_until,last_post_at FROM channel_members WHERE channel_id=?`
	args := []any{chID}
	if status != "" {
		q += ` AND status=?`
		args = append(args, status)
	}
	rows, err := s.db.Query(q+` ORDER BY joined_at`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []ChannelMember
	for rows.Next() {
		m := ChannelMember{ChannelID: chID}
		if err := rows.Scan(&m.IK, &m.Address, &m.Role, &m.Status, &m.JoinedAt, &m.MutedUntil, &m.LastPostAt); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

func (s *Store) ChannelMemberCount(chID string) (n int, err error) {
	err = s.db.QueryRow(`SELECT COUNT(*) FROM channel_members WHERE channel_id=? AND status<>'banned'`, chID).Scan(&n)
	return
}

// JoinChannel legt ein Mitglied an (status active oder pending). Gebannte werden abgewiesen, Bestehende unverändert zurückgegeben.
func (s *Store) JoinChannel(chID string, ik []byte, address, status string, maxMembers int) (*ChannelMember, bool, error) {
	if m, err := s.ChannelMember(chID, ik); err == nil {
		if m.Status == "banned" {
			return nil, false, ErrBanned
		}
		if address != "" && address != m.Address {
			_, _ = s.db.Exec(`UPDATE channel_members SET address=? WHERE channel_id=? AND ik=?`, address, chID, ik)
			m.Address = address
		}
		return m, false, nil
	}
	n, err := s.ChannelMemberCount(chID)
	if err != nil {
		return nil, false, err
	}
	if n >= maxMembers {
		return nil, false, ErrLimit
	}
	if _, err := s.db.Exec(`INSERT INTO channel_members(channel_id,ik,address,role,status,joined_at) VALUES(?,?,?,'member',?,?)`, chID, ik, address, status, now()); err != nil {
		return nil, false, err
	}
	m, err := s.ChannelMember(chID, ik)
	return m, true, err
}

func (s *Store) SetMember(chID string, ik []byte, role, status string, mutedUntil *int64) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if role != "" {
		if _, err := tx.Exec(`UPDATE channel_members SET role=? WHERE channel_id=? AND ik=?`, role, chID, ik); err != nil {
			return err
		}
	}
	if status != "" {
		if _, err := tx.Exec(`UPDATE channel_members SET status=? WHERE channel_id=? AND ik=?`, status, chID, ik); err != nil {
			return err
		}
	}
	if mutedUntil != nil {
		if _, err := tx.Exec(`UPDATE channel_members SET muted_until=? WHERE channel_id=? AND ik=?`, *mutedUntil, chID, ik); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func (s *Store) RemoveMember(chID string, ik []byte) error {
	_, err := s.db.Exec(`DELETE FROM channel_members WHERE channel_id=? AND ik=?`, chID, ik)
	return err
}

func (s *Store) TouchPost(chID string, ik []byte) error {
	_, err := s.db.Exec(`UPDATE channel_members SET last_post_at=? WHERE channel_id=? AND ik=?`, now(), chID, ik)
	return err
}

// AppendPost fügt einen (verschlüsselten, signierten) Beitrag an das Log an.
func (s *Store) AppendPost(chID, postID string, ik []byte, address string, tsMillis int64, epoch int, data, sig []byte) (int64, error) {
	res, err := s.db.Exec(`INSERT INTO channel_log(channel_id,type,post_id,ik,address,ts,epoch,data,sig) VALUES(?,'post',?,?,?,?,?,?,?)`,
		chID, postID, ik, address, tsMillis, epoch, data, sig)
	if err != nil {
		return 0, ErrConflict
	}
	return res.LastInsertId()
}

// AppendEvent fügt ein Moderations-/Mitgliedsereignis an.
func (s *Store) AppendEvent(chID, kind string, actor, target []byte, address string, meta map[string]any) (int64, error) {
	if meta == nil {
		meta = map[string]any{}
	}
	mj, _ := json.Marshal(meta)
	res, err := s.db.Exec(`INSERT INTO channel_log(channel_id,type,kind,ik,target,address,ts,meta) VALUES(?,'event',?,?,?,?,?,?)`,
		chID, kind, actor, target, address, time.Now().UnixMilli(), string(mj))
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}

// DeletePost löscht den Inhalt eines Beitrags (Tombstone bleibt).
func (s *Store) DeletePost(chID, postID string) (author []byte, err error) {
	err = s.db.QueryRow(`SELECT ik FROM channel_log WHERE channel_id=? AND post_id=? AND type='post'`, chID, postID).Scan(&author)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	_, err = s.db.Exec(`UPDATE channel_log SET deleted=1, data=NULL, sig=NULL WHERE channel_id=? AND post_id=?`, chID, postID)
	return author, err
}

func (s *Store) ChannelLog(chID string, after int64, limit int) ([]LogEntry, error) {
	rows, err := s.db.Query(`SELECT seq,type,COALESCE(post_id,''),ik,COALESCE(address,''),ts,epoch,data,sig,deleted,COALESCE(kind,''),target,COALESCE(meta,'') FROM channel_log WHERE channel_id=? AND seq>? ORDER BY seq LIMIT ?`, chID, after, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []LogEntry
	for rows.Next() {
		var e LogEntry
		var del int
		if err := rows.Scan(&e.Seq, &e.Type, &e.PostID, &e.IK, &e.Address, &e.TS, &e.Epoch, &e.Data, &e.Sig, &del, &e.Kind, &e.Target, &e.Meta); err != nil {
			return nil, err
		}
		e.Deleted = del == 1
		out = append(out, e)
	}
	return out, rows.Err()
}

func (s *Store) PurgeChannelLog(olderThan time.Duration) error {
	_, err := s.db.Exec(`DELETE FROM channel_log WHERE ts<?`, time.Now().Add(-olderThan).UnixMilli())
	return err
}
