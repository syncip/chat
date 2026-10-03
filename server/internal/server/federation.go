package server

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

var _, cgnat, _ = net.ParseCIDR("100.64.0.0/10")

var domainRe = regexp.MustCompile(`^[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?(:[0-9]{1,5})?$`)

func normDomain(d string) string { return strings.ToLower(strings.TrimSpace(d)) }
func validDomain(d string) bool  { return domainRe.MatchString(d) }

func splitAddress(a string) (name, domain string, ok bool) {
	n, d, found := strings.Cut(strings.ToLower(a), "@")
	d = normDomain(d)
	if !found || !nameRe.MatchString(n) || !validDomain(d) {
		return "", "", false
	}
	return n, d, true
}

type cachedKey struct {
	key     ed25519.PublicKey
	fetched time.Time
}

type federation struct {
	s      *Server
	client *http.Client
	mu     sync.Mutex
	keys   map[string]cachedKey
}

func newFederation(s *Server) *federation {
	d := &net.Dialer{
		Timeout: 10 * time.Second,
		// SSRF-Schutz: keine Verbindungen in private/lokale Netze (außer im Test-Modus).
		Control: func(network, address string, c syscall.RawConn) error {
			if s.cfg.FedInsecure {
				return nil
			}
			host, _, err := net.SplitHostPort(address)
			if err != nil {
				return err
			}
			ip := net.ParseIP(host)
			if ip != nil && cgnat.Contains(ip) {
				return errors.New("blocked address")
			}
			if ip == nil || ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast() ||
				ip.IsLinkLocalMulticast() || ip.IsUnspecified() || ip.IsMulticast() {
				return errors.New("blocked address")
			}
			return nil
		},
	}
	return &federation{
		s:    s,
		keys: map[string]cachedKey{},
		client: &http.Client{
			Timeout:   20 * time.Second,
			Transport: &http.Transport{DialContext: d.DialContext, MaxIdleConns: 20, IdleConnTimeout: 60 * time.Second},
			CheckRedirect: func(*http.Request, []*http.Request) error {
				return http.ErrUseLastResponse // keine Redirects folgen
			},
		},
	}
}

// isIPHost: Server ohne Domain, erreichbar über IP:PORT (kein TLS-Zertifikat möglich → http).
func isIPHost(domain string) bool {
	host := domain
	if i := strings.LastIndex(domain, ":"); i >= 0 {
		host = domain[:i]
	}
	return net.ParseIP(host) != nil
}

func (f *federation) base(domain string) string {
	if f.s.cfg.FedInsecure || isIPHost(domain) {
		return "http://" + domain
	}
	return "https://" + domain
}

// allowed wertet den Föderationsmodus des Betreibers aus (open | allowlist | closed + Blocklist).
func (f *federation) allowed(domain string) bool {
	c := f.s.cfg
	for _, b := range c.FedBlock {
		if b == domain {
			return false
		}
	}
	switch c.Federation {
	case "closed":
		return false
	case "allowlist":
		for _, a := range c.FedAllow {
			if a == domain {
				return true
			}
		}
		return false
	}
	return true
}

func (f *federation) serverKey(ctx context.Context, domain string) (ed25519.PublicKey, error) {
	f.mu.Lock()
	if c, ok := f.keys[domain]; ok && time.Since(c.fetched) < time.Hour {
		f.mu.Unlock()
		return c.key, nil
	}
	f.mu.Unlock()
	req, _ := http.NewRequestWithContext(ctx, "GET", f.base(domain)+"/.well-known/chat-server", nil)
	resp, err := f.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("well-known: %d", resp.StatusCode)
	}
	var wk struct {
		Domain    string `json:"domain"`
		ServerKey string `json:"server_key"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 16<<10)).Decode(&wk); err != nil {
		return nil, err
	}
	key, err := b64.DecodeString(wk.ServerKey)
	if err != nil || len(key) != ed25519.PublicKeySize || normDomain(wk.Domain) != domain {
		return nil, errors.New("invalid well-known")
	}
	f.mu.Lock()
	f.keys[domain] = cachedKey{key, time.Now()}
	f.mu.Unlock()
	return key, nil
}

func fedCanonical(target, ts, bodyHash string) []byte {
	return []byte("CHAT-FED-V1\n" + target + "\n" + ts + "\n" + bodyHash)
}

type fedBody struct {
	MailboxID string `json:"mailbox_id"`
	SendToken string `json:"send_token"`
	Data      string `json:"data"`
}

// deliver sendet signiert an den Remote-Server und liefert dessen HTTP-Status.
func (f *federation) deliver(ctx context.Context, domain, mailboxID, token string, data []byte) (int, error) {
	body, _ := json.Marshal(fedBody{mailboxID, token, b64.EncodeToString(data)})
	ts := strconv.FormatInt(time.Now().Unix(), 10)
	sum := sha256.Sum256(body)
	sig := ed25519.Sign(f.s.key, fedCanonical(domain, ts, hex.EncodeToString(sum[:])))
	req, _ := http.NewRequestWithContext(ctx, "POST", f.base(domain)+"/v1/federation/deliver", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Chat-Origin", f.s.cfg.Domain)
	req.Header.Set("X-Chat-Ts", ts)
	req.Header.Set("X-Chat-Sig", b64.EncodeToString(sig))
	resp, err := f.client.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4<<10))
	return resp.StatusCode, nil
}

func (f *federation) fetchKeyPackages(ctx context.Context, domain, name string, devices []string) ([]byte, error) {
	if !f.allowed(domain) {
		return nil, errors.New("not permitted")
	}
	q := ""
	for i, d := range devices {
		if !devRe.MatchString(d) {
			return nil, errors.New("invalid device")
		}
		if i == 0 {
			q = "?device=" + d
		} else {
			q += "&device=" + d
		}
	}
	req, _ := http.NewRequestWithContext(ctx, "GET", f.base(domain)+"/v1/users/"+name+"/keypackages"+q, nil)
	resp, err := f.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("status %d", resp.StatusCode)
	}
	return io.ReadAll(io.LimitReader(resp.Body, 64<<10))
}

// fedDeliver: eingehende Server-zu-Server-Zustellung.
func (s *Server) fedDeliver(w http.ResponseWriter, r *http.Request) {
	origin := normDomain(r.Header.Get("X-Chat-Origin"))
	ts := r.Header.Get("X-Chat-Ts")
	sigb, err := b64.DecodeString(r.Header.Get("X-Chat-Sig"))
	t, terr := strconv.ParseInt(ts, 10, 64)
	if !validDomain(origin) || err != nil || terr != nil || len(sigb) != ed25519.SignatureSize {
		writeErr(w, 401, "unauthorized")
		return
	}
	if d := time.Since(time.Unix(t, 0)); d > maxSkew || d < -maxSkew {
		writeErr(w, 401, "unauthorized")
		return
	}
	if !s.fed.allowed(origin) {
		writeErr(w, 403, "federation not permitted")
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, s.cfg.MaxEnvelopeSize*2+4096))
	if err != nil {
		writeErr(w, 413, "invalid size")
		return
	}
	key, err := s.fed.serverKey(r.Context(), origin)
	sum := sha256.Sum256(body)
	if err != nil || !ed25519.Verify(key, fedCanonical(s.cfg.Domain, ts, hex.EncodeToString(sum[:])), sigb) {
		writeErr(w, 401, "unauthorized")
		return
	}
	if s.nonces.seen("fed:" + origin + ":" + r.Header.Get("X-Chat-Sig")) {
		writeErr(w, 401, "unauthorized")
		return
	}
	var fb fedBody
	if !decodeJSON(w, body, &fb) {
		return
	}
	data, err := b64.DecodeString(fb.Data)
	if err != nil {
		writeErr(w, 400, "invalid data")
		return
	}
	s.deliverStatus(w, s.deliverLocal(fb.MailboxID, fb.SendToken, data, origin))
}
