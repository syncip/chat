// Package store kapselt die SQLite-Datenbank. Der Server speichert nur Chiffretext und
// minimale Zustell-Metadaten.
package store

import (
	"database/sql"
	"errors"
	"fmt"
	"path/filepath"
	"time"

	_ "modernc.org/sqlite"
)

var (
	ErrNotFound = errors.New("not found")
	ErrConflict = errors.New("conflict")
)

type Store struct{ db *sql.DB }

const schema = `
CREATE TABLE IF NOT EXISTS users(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL UNIQUE,
  ik BLOB NOT NULL,
  created_at INTEGER NOT NULL,
  filter_mode TEXT NOT NULL DEFAULT 'off',
  quota INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS invites(
  hash BLOB PRIMARY KEY,
  expires_at INTEGER NOT NULL,
  uses_left INTEGER NOT NULL,
  created_by INTEGER
);
CREATE TABLE IF NOT EXISTS keypackages(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  data BLOB NOT NULL,
  last_resort INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS kp_user ON keypackages(user_id, last_resort);
CREATE TABLE IF NOT EXISTS mailboxes(
  id TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash BLOB NOT NULL,
  intro INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS mb_user ON mailboxes(user_id);
CREATE TABLE IF NOT EXISTS messages(
  seq INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  mailbox_id TEXT NOT NULL,
  data BLOB NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS msg_user ON messages(user_id, seq);
CREATE TABLE IF NOT EXISTS blobs(
  id TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  size INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS blob_user ON blobs(user_id);
CREATE TABLE IF NOT EXISTS filters(
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  domain_hash BLOB NOT NULL,
  PRIMARY KEY(user_id, domain_hash)
);
CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v BLOB NOT NULL);
`

func Open(dir string) (*Store, error) {
	db, err := sql.Open("sqlite", "file:"+filepath.Join(dir, "chat.db")+
		"?_pragma=journal_mode(WAL)&_pragma=foreign_keys(1)&_pragma=busy_timeout(5000)&_pragma=synchronous(NORMAL)")
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1) // SQLite: ein Schreiber; vermeidet Sperrkonflikte
	if _, err := db.Exec(schema); err != nil {
		return nil, fmt.Errorf("schema: %w", err)
	}
	return &Store{db}, nil
}

func (s *Store) Close() error { return s.db.Close() }

func now() int64 { return time.Now().Unix() }

// ---- Meta (Server-Schlüssel) ----

func (s *Store) Meta(k string) ([]byte, error) {
	var v []byte
	err := s.db.QueryRow(`SELECT v FROM meta WHERE k=?`, k).Scan(&v)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return v, err
}

func (s *Store) SetMeta(k string, v []byte) error {
	_, err := s.db.Exec(`INSERT INTO meta(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v`, k, v)
	return err
}

// ---- Nutzer ----

type User struct {
	ID         int64
	Name       string
	IK         []byte
	FilterMode string
	Quota      int64
}

func (s *Store) UserByName(name string) (*User, error) {
	u := &User{}
	err := s.db.QueryRow(`SELECT id,name,ik,filter_mode,quota FROM users WHERE name=?`, name).
		Scan(&u.ID, &u.Name, &u.IK, &u.FilterMode, &u.Quota)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return u, err
}

func (s *Store) UserCount() (n int, err error) {
	err = s.db.QueryRow(`SELECT COUNT(*) FROM users`).Scan(&n)
	return
}

// Register legt Nutzer, Intro-Postfach und (optional) KeyPackages atomar an; verbraucht die Einladung.
func (s *Store) Register(inviteHash []byte, name string, ik []byte, intro MailboxInit, kps [][]byte, lastResort []byte) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if inviteHash != nil {
		res, err := tx.Exec(`UPDATE invites SET uses_left=uses_left-1 WHERE hash=? AND uses_left>0 AND expires_at>?`, inviteHash, now())
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return ErrNotFound
		}
	}
	res, err := tx.Exec(`INSERT INTO users(name,ik,created_at) VALUES(?,?,?)`, name, ik, now())
	if err != nil {
		return ErrConflict
	}
	uid, _ := res.LastInsertId()
	if _, err := tx.Exec(`INSERT INTO mailboxes(id,user_id,token_hash,intro) VALUES(?,?,?,1)`, intro.ID, uid, intro.TokenHash); err != nil {
		return err
	}
	for _, kp := range kps {
		if _, err := tx.Exec(`INSERT INTO keypackages(user_id,data) VALUES(?,?)`, uid, kp); err != nil {
			return err
		}
	}
	if lastResort != nil {
		if _, err := tx.Exec(`INSERT INTO keypackages(user_id,data,last_resort) VALUES(?,?,1)`, uid, lastResort); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func (s *Store) SetFilterMode(uid int64, mode string) error {
	_, err := s.db.Exec(`UPDATE users SET filter_mode=? WHERE id=?`, mode, uid)
	return err
}

func (s *Store) ReplaceFilters(uid int64, hashes [][]byte) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.Exec(`DELETE FROM filters WHERE user_id=?`, uid); err != nil {
		return err
	}
	for _, h := range hashes {
		if _, err := tx.Exec(`INSERT OR IGNORE INTO filters(user_id,domain_hash) VALUES(?,?)`, uid, h); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// ---- Einladungen ----

func (s *Store) CreateInvite(hash []byte, ttl time.Duration, uses int, by int64) error {
	_, err := s.db.Exec(`INSERT INTO invites(hash,expires_at,uses_left,created_by) VALUES(?,?,?,?)`,
		hash, time.Now().Add(ttl).Unix(), uses, by)
	return err
}

// ---- KeyPackages ----

func (s *Store) AddKeyPackages(uid int64, kps [][]byte, lastResort []byte, max int) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var n int
	if err := tx.QueryRow(`SELECT COUNT(*) FROM keypackages WHERE user_id=? AND last_resort=0`, uid).Scan(&n); err != nil {
		return err
	}
	if n+len(kps) > max {
		return ErrConflict
	}
	for _, kp := range kps {
		if _, err := tx.Exec(`INSERT INTO keypackages(user_id,data) VALUES(?,?)`, uid, kp); err != nil {
			return err
		}
	}
	if lastResort != nil {
		if _, err := tx.Exec(`DELETE FROM keypackages WHERE user_id=? AND last_resort=1`, uid); err != nil {
			return err
		}
		if _, err := tx.Exec(`INSERT INTO keypackages(user_id,data,last_resort) VALUES(?,?,1)`, uid, lastResort); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func (s *Store) KeyPackageCount(uid int64) (n int, err error) {
	err = s.db.QueryRow(`SELECT COUNT(*) FROM keypackages WHERE user_id=? AND last_resort=0`, uid).Scan(&n)
	return
}

// TakeKeyPackage liefert ein Einmal-KeyPackage (und löscht es) oder das Last-Resort-Paket.
func (s *Store) TakeKeyPackage(uid int64) ([]byte, error) {
	tx, err := s.db.Begin()
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	var id int64
	var data []byte
	err = tx.QueryRow(`SELECT id,data FROM keypackages WHERE user_id=? AND last_resort=0 ORDER BY id LIMIT 1`, uid).Scan(&id, &data)
	if err == nil {
		if _, err := tx.Exec(`DELETE FROM keypackages WHERE id=?`, id); err != nil {
			return nil, err
		}
		return data, tx.Commit()
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return nil, err
	}
	err = tx.QueryRow(`SELECT data FROM keypackages WHERE user_id=? AND last_resort=1`, uid).Scan(&data)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return data, err
}

// ---- Postfächer ----

type MailboxInit struct {
	ID        string
	TokenHash []byte
}

type Mailbox struct {
	ID        string
	UserID    int64
	TokenHash []byte
	Intro     bool
}

func (s *Store) CreateMailbox(uid int64, m MailboxInit, max int) error {
	var n int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM mailboxes WHERE user_id=?`, uid).Scan(&n); err != nil {
		return err
	}
	if n >= max {
		return ErrConflict
	}
	_, err := s.db.Exec(`INSERT INTO mailboxes(id,user_id,token_hash) VALUES(?,?,?)`, m.ID, uid, m.TokenHash)
	return err
}

func (s *Store) Mailbox(id string) (*Mailbox, error) {
	m := &Mailbox{}
	var intro int
	err := s.db.QueryRow(`SELECT id,user_id,token_hash,intro FROM mailboxes WHERE id=?`, id).
		Scan(&m.ID, &m.UserID, &m.TokenHash, &intro)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	m.Intro = intro == 1
	return m, err
}

func (s *Store) ListMailboxes(uid int64) ([]Mailbox, error) {
	rows, err := s.db.Query(`SELECT id,intro FROM mailboxes WHERE user_id=?`, uid)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Mailbox
	for rows.Next() {
		var m Mailbox
		var i int
		if err := rows.Scan(&m.ID, &i); err != nil {
			return nil, err
		}
		m.Intro = i == 1
		m.UserID = uid
		out = append(out, m)
	}
	return out, rows.Err()
}

// DeleteMailbox widerruft ein Postfach samt noch nicht abgeholter Nachrichten (= Kontakt blockieren).
func (s *Store) DeleteMailbox(uid int64, id string) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	res, err := tx.Exec(`DELETE FROM mailboxes WHERE id=? AND user_id=?`, id, uid)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	if _, err := tx.Exec(`DELETE FROM messages WHERE mailbox_id=? AND user_id=?`, id, uid); err != nil {
		return err
	}
	return tx.Commit()
}

// ---- Nachrichten ----

type Message struct {
	Seq       int64
	MailboxID string
	Data      []byte
}

func (s *Store) Deliver(uid int64, mailboxID string, data []byte) (int64, error) {
	res, err := s.db.Exec(`INSERT INTO messages(user_id,mailbox_id,data,created_at) VALUES(?,?,?,?)`, uid, mailboxID, data, now())
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}

func (s *Store) Messages(uid, after int64, limit int) ([]Message, error) {
	rows, err := s.db.Query(`SELECT seq,mailbox_id,data FROM messages WHERE user_id=? AND seq>? ORDER BY seq LIMIT ?`, uid, after, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Message
	for rows.Next() {
		var m Message
		if err := rows.Scan(&m.Seq, &m.MailboxID, &m.Data); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

func (s *Store) AckMessages(uid, upto int64) error {
	_, err := s.db.Exec(`DELETE FROM messages WHERE user_id=? AND seq<=?`, uid, upto)
	return err
}

// ---- Filter ----

// OriginBlocked wertet den Server-Filter des Nutzers (Block- oder Allowlist über gehashte Domains) aus.
func (s *Store) OriginBlocked(uid int64, mode string, domainHash []byte) (bool, error) {
	if mode == "off" || mode == "" {
		return false, nil
	}
	var n int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM filters WHERE user_id=? AND domain_hash=?`, uid, domainHash).Scan(&n); err != nil {
		return false, err
	}
	if mode == "block" {
		return n > 0, nil
	}
	return n == 0, nil // allow
}

// ---- Blobs ----

func (s *Store) BlobUsage(uid int64) (n int64, err error) {
	err = s.db.QueryRow(`SELECT COALESCE(SUM(size),0) FROM blobs WHERE user_id=?`, uid).Scan(&n)
	return
}

func (s *Store) AddBlob(uid int64, id string, size int64, ttl time.Duration) error {
	_, err := s.db.Exec(`INSERT INTO blobs(id,user_id,size,expires_at) VALUES(?,?,?,?)`, id, uid, size, time.Now().Add(ttl).Unix())
	return err
}

func (s *Store) BlobOwner(id string) (int64, error) {
	var uid int64
	err := s.db.QueryRow(`SELECT user_id FROM blobs WHERE id=? AND expires_at>?`, id, now()).Scan(&uid)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, ErrNotFound
	}
	return uid, err
}

func (s *Store) DeleteBlob(uid int64, id string) error {
	res, err := s.db.Exec(`DELETE FROM blobs WHERE id=? AND user_id=?`, id, uid)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// ExpiredBlobs entfernt abgelaufene Blob-Einträge und liefert deren IDs (Dateien löscht der Aufrufer).
func (s *Store) ExpiredBlobs() ([]string, error) {
	rows, err := s.db.Query(`SELECT id FROM blobs WHERE expires_at<=?`, now())
	if err != nil {
		return nil, err
	}
	var ids []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return nil, err
		}
		ids = append(ids, id)
	}
	rows.Close()
	if _, err := s.db.Exec(`DELETE FROM blobs WHERE expires_at<=?`, now()); err != nil {
		return nil, err
	}
	return ids, nil
}

func (s *Store) PurgeMessages(olderThan time.Duration) error {
	_, err := s.db.Exec(`DELETE FROM messages WHERE created_at<?`, time.Now().Add(-olderThan).Unix())
	if err == nil {
		_, err = s.db.Exec(`DELETE FROM invites WHERE expires_at<? OR uses_left<=0`, now())
	}
	return err
}

func (s *Store) UserByID(id int64) (*User, error) {
	u := &User{}
	err := s.db.QueryRow(`SELECT id,name,ik,filter_mode,quota FROM users WHERE id=?`, id).
		Scan(&u.ID, &u.Name, &u.IK, &u.FilterMode, &u.Quota)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return u, err
}
