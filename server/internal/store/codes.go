package store

import (
	"database/sql"
	"errors"
)

// Chat-Codes: frei wählbare, öffentlich auflösbare Kurzcodes (z. B. „martinistcool“), hinter denen die Kontaktkarte des Kontos liegt.
const codeSchema = `
CREATE TABLE IF NOT EXISTS chat_codes(
  code TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
  card TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
`

// SetCode vergibt oder ersetzt den Code des Kontos. ErrConflict, wenn ein anderes Konto ihn bereits hat.
func (s *Store) SetCode(userID int64, code, card string) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var owner int64
	switch err := tx.QueryRow(`SELECT user_id FROM chat_codes WHERE code=?`, code).Scan(&owner); {
	case err == nil && owner != userID:
		return ErrConflict
	case err != nil && !errors.Is(err, sql.ErrNoRows):
		return err
	}
	if _, err := tx.Exec(`DELETE FROM chat_codes WHERE user_id=?`, userID); err != nil {
		return err
	}
	if _, err := tx.Exec(`INSERT INTO chat_codes(code,user_id,card,created_at) VALUES(?,?,?,?)`, code, userID, card, now()); err != nil {
		return ErrConflict
	}
	return tx.Commit()
}

func (s *Store) DeleteCode(userID int64) error {
	_, err := s.db.Exec(`DELETE FROM chat_codes WHERE user_id=?`, userID)
	return err
}

func (s *Store) CodeOf(userID int64) (string, error) {
	var c string
	err := s.db.QueryRow(`SELECT code FROM chat_codes WHERE user_id=?`, userID).Scan(&c)
	if errors.Is(err, sql.ErrNoRows) {
		return "", ErrNotFound
	}
	return c, err
}

// ResolveCode liefert die Kontaktkarte zu einem Code. Gesperrte Konten lösen nicht mehr auf.
func (s *Store) ResolveCode(code string) (string, error) {
	var card string
	var banned int64
	err := s.db.QueryRow(`SELECT c.card,u.banned_until FROM chat_codes c JOIN users u ON u.id=c.user_id WHERE c.code=?`, code).Scan(&card, &banned)
	if errors.Is(err, sql.ErrNoRows) {
		return "", ErrNotFound
	}
	if err != nil {
		return "", err
	}
	if banned == -1 || banned > now() {
		return "", ErrNotFound
	}
	return card, nil
}
