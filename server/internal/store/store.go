// Package store kapselt die SQLite-Datenbank. Der Server speichert nur Chiffretext und
// minimale Zustell-Metadaten.
package store

import (
	"database/sql"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"time"

	_ "modernc.org/sqlite"
)

var (
	ErrNotFound = errors.New("not found")
	ErrConflict = errors.New("conflict")
	ErrLimit    = errors.New("limit reached")
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
CREATE TABLE IF NOT EXISTS devices(
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  id TEXT NOT NULL,
  dpk BLOB NOT NULL,
  cert BLOB NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY(user_id, id)
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
  device_id TEXT NOT NULL,
  data BLOB NOT NULL,
  last_resort INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS kp_user ON keypackages(user_id, device_id, last_resort);
CREATE TABLE IF NOT EXISTS mailboxes(
  id TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash BLOB NOT NULL,
  intro INTEGER NOT NULL DEFAULT 0,
  device_id TEXT NOT NULL DEFAULT ''  -- leer: kontoweit (Zustellung an alle Geräte); sonst nur dieses Gerät (Geräte-Postfach)
);
CREATE INDEX IF NOT EXISTS mb_user ON mailboxes(user_id);
CREATE TABLE IF NOT EXISTS messages(
  seq INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_id TEXT NOT NULL,
  mailbox_id TEXT NOT NULL,
  data BLOB NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS msg_dev ON messages(user_id, device_id, seq);
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
CREATE TABLE IF NOT EXISTS account_sync(
  user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  version INTEGER NOT NULL,
  data BLOB NOT NULL,
  updated_at INTEGER NOT NULL
);
`

// SchemaVersion: 2 = Multi-Device. Ältere Datenbanken sind inkompatibel (Credentials/Protokoll) und werden beiseitegelegt.
const SchemaVersion = 2

func open(path string) (*sql.DB, error) {
	db, err := sql.Open("sqlite", "file:"+path+
		"?_pragma=journal_mode(WAL)&_pragma=foreign_keys(1)&_pragma=busy_timeout(5000)&_pragma=synchronous(NORMAL)")
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1) // SQLite: ein Schreiber; vermeidet Sperrkonflikte
	return db, nil
}

func Open(dir string) (*Store, error) {
	path := filepath.Join(dir, "chat.db")
	db, err := open(path)
	if err != nil {
		return nil, err
	}
	// Vorhandene Datenbank ohne devices-Tabelle = Schema v1 (vor Multi-Device).
	var hasUsers, hasDevices int
	_ = db.QueryRow(`SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='users'`).Scan(&hasUsers)
	_ = db.QueryRow(`SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='devices'`).Scan(&hasDevices)
	if hasUsers > 0 && hasDevices == 0 {
		db.Close()
		bak := path + ".bak-v1"
		if err := os.Rename(path, bak); err != nil {
			return nil, fmt.Errorf("alte Datenbank sichern: %w", err)
		}
		for _, ext := range []string{"-wal", "-shm"} {
			_ = os.Rename(path+ext, bak+ext)
		}
		slog.Warn("Datenbank-Schema v1 ist mit Multi-Device inkompatibel: Konten müssen neu angelegt werden", "backup", bak)
		if db, err = open(path); err != nil {
			return nil, err
		}
	}
	if _, err := db.Exec(schema + channelSchema + hookSchema); err != nil {
		return nil, fmt.Errorf("schema: %w", err)
	}
	st := &Store{db}
	if err := st.migrate(); err != nil {
		return nil, fmt.Errorf("migrate: %w", err)
	}
	if err := st.SetMeta("schema_version", []byte(fmt.Sprint(SchemaVersion))); err != nil {
		return nil, err
	}
	return st, nil
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
	Admin      bool
	CreatedAt  int64
	ID         int64
	Name       string
	IK         []byte
	FilterMode string
	Quota      int64
}

func (s *Store) UserByName(name string) (*User, error) {
	u := &User{}
	var adm int
	err := s.db.QueryRow(`SELECT id,name,ik,filter_mode,quota,is_admin,created_at FROM users WHERE name=?`, name).
		Scan(&u.ID, &u.Name, &u.IK, &u.FilterMode, &u.Quota, &adm, &u.CreatedAt)
	u.Admin = adm == 1
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return u, err
}

func (s *Store) UserCount() (n int, err error) {
	err = s.db.QueryRow(`SELECT COUNT(*) FROM users`).Scan(&n)
	return
}

// Device ist ein Gerät eines Kontos (siehe docs/MULTIDEVICE.md).
type Device struct {
	ID        string
	UserID    int64
	DPK       []byte // öffentlicher Geräteschlüssel (Anfragen-Signatur)
	Cert      []byte // Zertifikat des Konto-Schlüssels über (Adresse, Geräte-ID, DPK)
	CreatedAt int64
}

// Inbox ist ein Postfach nur für ein Gerät (ID/Token deterministisch aus dem Konto-Schlüssel abgeleitet).
type Inbox struct {
	MailboxID string
	TokenHash []byte
}

// Register legt Nutzer, erstes Gerät, Geräte-Inbox, Intro-Postfach und KeyPackages atomar an; verbraucht die Einladung.
func (s *Store) Register(inviteHash []byte, name string, ik []byte, dev Device, inbox Inbox, intro MailboxInit, kps [][]byte, lastResort []byte) error {
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
	var existing int
	if err := tx.QueryRow(`SELECT COUNT(*) FROM users`).Scan(&existing); err != nil {
		return err
	}
	admin := 0
	if existing == 0 { // der erste Nutzer ist Administrator
		admin = 1
	}
	res, err := tx.Exec(`INSERT INTO users(name,ik,created_at,is_admin) VALUES(?,?,?,?)`, name, ik, now(), admin)
	if err != nil {
		return ErrConflict
	}
	uid, _ := res.LastInsertId()
	if err := insertDevice(tx, uid, dev, inbox, kps, lastResort); err != nil {
		return err
	}
	if _, err := tx.Exec(`INSERT INTO mailboxes(id,user_id,token_hash,intro) VALUES(?,?,?,1)`, intro.ID, uid, intro.TokenHash); err != nil {
		return err
	}
	return tx.Commit()
}

func insertDevice(tx *sql.Tx, uid int64, dev Device, inbox Inbox, kps [][]byte, lastResort []byte) error {
	if _, err := tx.Exec(`INSERT INTO devices(user_id,id,dpk,cert,created_at) VALUES(?,?,?,?,?)`, uid, dev.ID, dev.DPK, dev.Cert, now()); err != nil {
		return ErrConflict
	}
	if _, err := tx.Exec(`INSERT INTO mailboxes(id,user_id,token_hash,device_id) VALUES(?,?,?,?)`, inbox.MailboxID, uid, inbox.TokenHash, dev.ID); err != nil {
		return ErrConflict
	}
	for _, kp := range kps {
		if _, err := tx.Exec(`INSERT INTO keypackages(user_id,device_id,data) VALUES(?,?,?)`, uid, dev.ID, kp); err != nil {
			return err
		}
	}
	if lastResort != nil {
		if _, err := tx.Exec(`INSERT INTO keypackages(user_id,device_id,data,last_resort) VALUES(?,?,?,1)`, uid, dev.ID, lastResort); err != nil {
			return err
		}
	}
	return nil
}

// AddDevice fügt einem bestehenden Konto ein Gerät hinzu (höchstens `max` aktive Geräte).
func (s *Store) AddDevice(uid int64, dev Device, inbox Inbox, kps [][]byte, lastResort []byte, max int) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var n int
	if err := tx.QueryRow(`SELECT COUNT(*) FROM devices WHERE user_id=?`, uid).Scan(&n); err != nil {
		return err
	}
	if n >= max {
		return ErrLimit
	}
	if err := insertDevice(tx, uid, dev, inbox, kps, lastResort); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *Store) DeviceByID(uid int64, id string) (*Device, error) {
	d := &Device{UserID: uid}
	err := s.db.QueryRow(`SELECT id,dpk,cert,created_at FROM devices WHERE user_id=? AND id=?`, uid, id).Scan(&d.ID, &d.DPK, &d.Cert, &d.CreatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return d, err
}

func (s *Store) ListDevices(uid int64) ([]Device, error) {
	rows, err := s.db.Query(`SELECT id,dpk,cert,created_at FROM devices WHERE user_id=? ORDER BY id`, uid)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Device
	for rows.Next() {
		d := Device{UserID: uid}
		if err := rows.Scan(&d.ID, &d.DPK, &d.Cert, &d.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

// RevokeDevice entfernt ein Gerät samt KeyPackages, Geräte-Inbox und wartenden Nachrichten.
func (s *Store) RevokeDevice(uid int64, id string) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	res, err := tx.Exec(`DELETE FROM devices WHERE user_id=? AND id=?`, uid, id)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	for _, q := range []string{
		`DELETE FROM keypackages WHERE user_id=? AND device_id=?`,
		`DELETE FROM mailboxes WHERE user_id=? AND device_id=?`,
		`DELETE FROM messages WHERE user_id=? AND device_id=?`,
	} {
		if _, err := tx.Exec(q, uid, id); err != nil {
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

func (s *Store) AddKeyPackages(uid int64, deviceID string, kps [][]byte, lastResort []byte, max int) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var n int
	if err := tx.QueryRow(`SELECT COUNT(*) FROM keypackages WHERE user_id=? AND device_id=? AND last_resort=0`, uid, deviceID).Scan(&n); err != nil {
		return err
	}
	if n+len(kps) > max {
		return ErrConflict
	}
	for _, kp := range kps {
		if _, err := tx.Exec(`INSERT INTO keypackages(user_id,device_id,data) VALUES(?,?,?)`, uid, deviceID, kp); err != nil {
			return err
		}
	}
	if lastResort != nil {
		if _, err := tx.Exec(`DELETE FROM keypackages WHERE user_id=? AND device_id=? AND last_resort=1`, uid, deviceID); err != nil {
			return err
		}
		if _, err := tx.Exec(`INSERT INTO keypackages(user_id,device_id,data,last_resort) VALUES(?,?,?,1)`, uid, deviceID, lastResort); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func (s *Store) KeyPackageCount(uid int64, deviceID string) (n int, err error) {
	err = s.db.QueryRow(`SELECT COUNT(*) FROM keypackages WHERE user_id=? AND device_id=? AND last_resort=0`, uid, deviceID).Scan(&n)
	return
}

// DeviceKeyPackage ist ein KeyPackage eines Geräts.
type DeviceKeyPackage struct {
	DeviceID string
	Data     []byte
}

// TakeKeyPackages liefert je Gerät ein Einmal-KeyPackage (und löscht es) oder das Last-Resort-Paket.
func (s *Store) TakeKeyPackages(uid int64, only []string) ([]DeviceKeyPackage, error) {
	tx, err := s.db.Begin()
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	rows, err := tx.Query(`SELECT id FROM devices WHERE user_id=? ORDER BY id`, uid)
	if err != nil {
		return nil, err
	}
	var devs []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return nil, err
		}
		devs = append(devs, id)
	}
	rows.Close()
	var out []DeviceKeyPackage
	for _, dev := range devs {
		if len(only) > 0 && !contains(only, dev) {
			continue
		}
		var id int64
		var data []byte
		err := tx.QueryRow(`SELECT id,data FROM keypackages WHERE user_id=? AND device_id=? AND last_resort=0 ORDER BY id LIMIT 1`, uid, dev).Scan(&id, &data)
		if err == nil {
			if _, err := tx.Exec(`DELETE FROM keypackages WHERE id=?`, id); err != nil {
				return nil, err
			}
			out = append(out, DeviceKeyPackage{dev, data})
			continue
		}
		if !errors.Is(err, sql.ErrNoRows) {
			return nil, err
		}
		err = tx.QueryRow(`SELECT data FROM keypackages WHERE user_id=? AND device_id=? AND last_resort=1`, uid, dev).Scan(&data)
		if errors.Is(err, sql.ErrNoRows) {
			continue // Gerät ohne KeyPackages (noch nicht bereit)
		}
		if err != nil {
			return nil, err
		}
		out = append(out, DeviceKeyPackage{dev, data})
	}
	if len(out) == 0 {
		return nil, ErrNotFound
	}
	return out, tx.Commit()
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
	DeviceID  string // leer: kontoweit
}

// CreateMailbox legt ein Postfach **nur für das Gerät** `deviceID` an (leer: kontoweit).
func (s *Store) CreateMailbox(uid int64, deviceID string, intro bool, m MailboxInit, max int) error {
	var n int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM mailboxes WHERE user_id=? AND device_id=?`, uid, deviceID).Scan(&n); err != nil {
		return err
	}
	if n >= max {
		return ErrConflict
	}
	in := 0
	if intro {
		in = 1
	}
	_, err := s.db.Exec(`INSERT INTO mailboxes(id,user_id,token_hash,intro,device_id) VALUES(?,?,?,?,?)`, m.ID, uid, m.TokenHash, in, deviceID)
	return err
}

func (s *Store) Mailbox(id string) (*Mailbox, error) {
	m := &Mailbox{}
	var intro int
	err := s.db.QueryRow(`SELECT id,user_id,token_hash,intro,device_id FROM mailboxes WHERE id=?`, id).
		Scan(&m.ID, &m.UserID, &m.TokenHash, &intro, &m.DeviceID)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	m.Intro = intro == 1
	return m, err
}

func (s *Store) ListMailboxes(uid int64, deviceID string) ([]Mailbox, error) {
	rows, err := s.db.Query(`SELECT id,intro,device_id FROM mailboxes WHERE user_id=? AND (device_id='' OR device_id=?)`, uid, deviceID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Mailbox
	for rows.Next() {
		var m Mailbox
		var i int
		if err := rows.Scan(&m.ID, &i, &m.DeviceID); err != nil {
			return nil, err
		}
		m.Intro = i == 1
		m.UserID = uid
		out = append(out, m)
	}
	return out, rows.Err()
}

// DeleteMailbox widerruft ein Postfach samt noch nicht abgeholter Nachrichten (= Kontakt blockieren).
func (s *Store) DeleteMailbox(uid int64, deviceID, id string) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	res, err := tx.Exec(`DELETE FROM mailboxes WHERE id=? AND user_id=? AND (device_id='' OR device_id=?)`, id, uid, deviceID)
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

// Deliver legt die Nachricht für jedes aktive Gerät des Kontos (bzw. nur für das Gerät eines Geräte-Postfachs) in die Warteschlange.
// Gibt (Gerät → seq) zurück.
func (s *Store) Deliver(uid int64, mailboxID, onlyDevice string, data []byte) (map[string]int64, error) {
	tx, err := s.db.Begin()
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	var devs []string
	if onlyDevice != "" {
		devs = []string{onlyDevice}
	} else {
		rows, err := tx.Query(`SELECT id FROM devices WHERE user_id=?`, uid)
		if err != nil {
			return nil, err
		}
		for rows.Next() {
			var id string
			if err := rows.Scan(&id); err != nil {
				rows.Close()
				return nil, err
			}
			devs = append(devs, id)
		}
		rows.Close()
	}
	out := map[string]int64{}
	for _, d := range devs {
		res, err := tx.Exec(`INSERT INTO messages(user_id,device_id,mailbox_id,data,created_at) VALUES(?,?,?,?,?)`, uid, d, mailboxID, data, now())
		if err != nil {
			return nil, err
		}
		out[d], _ = res.LastInsertId()
	}
	return out, tx.Commit()
}

func (s *Store) Messages(uid int64, deviceID string, after int64, limit int) ([]Message, error) {
	rows, err := s.db.Query(`SELECT seq,mailbox_id,data FROM messages WHERE user_id=? AND device_id=? AND seq>? ORDER BY seq LIMIT ?`, uid, deviceID, after, limit)
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

func (s *Store) AckMessages(uid int64, deviceID string, upto int64) error {
	_, err := s.db.Exec(`DELETE FROM messages WHERE user_id=? AND device_id=? AND seq<=?`, uid, deviceID, upto)
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

func contains(l []string, v string) bool {
	for _, x := range l {
		if x == v {
			return true
		}
	}
	return false
}

// migrate ergänzt Spalten älterer Datenbanken und bestimmt ggf. den Administrator (ältester Nutzer).
func (s *Store) migrate() error {
	addCol := func(table, col, def string) error {
		rows, err := s.db.Query(`PRAGMA table_info(` + table + `)`)
		if err != nil {
			return err
		}
		has := false
		for rows.Next() {
			var cid int
			var name, typ string
			var notnull, pk int
			var dflt sql.NullString
			if err := rows.Scan(&cid, &name, &typ, &notnull, &dflt, &pk); err != nil {
				rows.Close()
				return err
			}
			if name == col {
				has = true
			}
		}
		rows.Close()
		if has {
			return nil
		}
		_, err = s.db.Exec(`ALTER TABLE ` + table + ` ADD COLUMN ` + col + ` ` + def)
		return err
	}
	if err := addCol("users", "is_admin", "INTEGER NOT NULL DEFAULT 0"); err != nil {
		return err
	}
	if err := addCol("channel_log", "hook", "TEXT"); err != nil {
		return err
	}
	var n int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM users WHERE is_admin=1`).Scan(&n); err != nil {
		return err
	}
	if n == 0 {
		_, err := s.db.Exec(`UPDATE users SET is_admin=1 WHERE id=(SELECT MIN(id) FROM users)`)
		return err
	}
	return nil
}
