package server

import (
	"crypto/sha256"
	"encoding/hex"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"
)

// ---- Nonce-Cache (Replay-Schutz) ----

type nonceCache struct {
	mu  sync.Mutex
	ttl time.Duration
	m   map[string]time.Time
}

func newNonceCache(ttl time.Duration) *nonceCache {
	return &nonceCache{ttl: ttl, m: map[string]time.Time{}}
}

// seen liefert true, wenn der Nonce schon benutzt wurde; merkt ihn sonst.
func (c *nonceCache) seen(k string) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	if _, ok := c.m[k]; ok {
		return true
	}
	c.m[k] = time.Now()
	return false
}

func (c *nonceCache) sweep() {
	c.mu.Lock()
	defer c.mu.Unlock()
	for k, t := range c.m {
		if time.Since(t) > c.ttl {
			delete(c.m, k)
		}
	}
}

// ---- Rate-Limiter (Token Bucket pro IP, nur im Speicher) ----

type bucket struct {
	tokens float64
	last   time.Time
}

type limiter struct {
	mu   sync.Mutex
	rate float64 // Tokens pro Sekunde
	max  float64
	m    map[string]*bucket
}

func newLimiter(perMinute int) *limiter {
	if perMinute <= 0 {
		perMinute = 120
	}
	return &limiter{rate: float64(perMinute) / 60, max: float64(perMinute), m: map[string]*bucket{}}
}

func (l *limiter) allow(ip string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := time.Now()
	b, ok := l.m[ip]
	if !ok {
		b = &bucket{tokens: l.max, last: now}
		l.m[ip] = b
	}
	b.tokens += now.Sub(b.last).Seconds() * l.rate
	if b.tokens > l.max {
		b.tokens = l.max
	}
	b.last = now
	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}

func (l *limiter) sweep() {
	l.mu.Lock()
	defer l.mu.Unlock()
	for k, b := range l.m {
		if time.Since(b.last) > 10*time.Minute {
			delete(l.m, k)
		}
	}
}

// hashDir bildet einen Hash über alle Dateien des Web-Bundles (Pfad + Inhalt).
func hashDir(dir string) (string, error) {
	var files []string
	err := filepath.WalkDir(dir, func(p string, d fs.DirEntry, err error) error {
		if err == nil && d.Type().IsRegular() {
			files = append(files, p)
		}
		return err
	})
	if err != nil {
		return "", err
	}
	sort.Strings(files)
	h := sha256.New()
	for _, f := range files {
		rel, _ := filepath.Rel(dir, f)
		b, err := os.ReadFile(f)
		if err != nil {
			return "", err
		}
		h.Write([]byte(filepath.ToSlash(rel)))
		h.Write([]byte{0})
		sum := sha256.Sum256(b)
		h.Write(sum[:])
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}
