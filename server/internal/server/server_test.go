package server_test

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base32"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/syncip/chat/server/internal/config"
	"github.com/syncip/chat/server/internal/server"
	"github.com/syncip/chat/server/internal/store"
)

var b64 = base64.StdEncoding

type node struct {
	t      *testing.T
	cfg    *config.Config
	srv    *server.Server
	ts     *httptest.Server
	domain string
}

func newNode(t *testing.T, mod func(*config.Config)) *node {
	t.Helper()
	ts := httptest.NewUnstartedServer(nil)
	domain := ts.Listener.Addr().String()
	dir := t.TempDir()
	cfg := &config.Config{
		Domain: domain, DataDir: dir, AdminKey: "adminkey-for-tests", Registration: "invite",
		MaxFileSize: 1 << 20, MaxAttachments: 10, MaxMessageTotal: 5 << 20, MaxMessageText: 1 << 16,
		MaxEnvelopeSize: 64 << 10, UserQuota: 3 << 20, BlobRetention: time.Hour, MessageRetention: time.Hour,
		Federation: "open", FedInsecure: true, RatePerMinute: 100000, MaxMailboxes: 50, MaxKeyPackages: 5,
		UserInvites: true, RegistrationPoW: 8, MaxDevices: 10,
		Channels: true, MaxChannels: 10, MaxChannelUsers: 100, MaxPostSize: 64 << 10, ChannelRetention: time.Hour,
	}
	if mod != nil {
		mod(cfg)
	}
	st, err := store.Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	srv, err := server.New(cfg, st, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	ts.Config.Handler = srv.Handler()
	ts.Start()
	t.Cleanup(func() { srv.Close(); ts.Close(); st.Close() })
	return &node{t: t, cfg: cfg, srv: srv, ts: ts, domain: domain}
}

// device: Geräteschlüssel (DSK) + Geräte-ID; das Konto (AIK) beglaubigt es per Zertifikat.
type device struct {
	id   string
	pub  ed25519.PublicKey
	priv ed25519.PrivateKey
}

type user struct {
	n    *node
	name string
	pub  ed25519.PublicKey // AIK
	priv ed25519.PrivateKey
	dev  device
	// inboxID: Geräte-Postfach (nur bei per addDevice erzeugten Nutzern gesetzt)
	inboxID string
	intro   struct{ ID, Token string }
}

func (n *node) newDevice(name string, aik ed25519.PrivateKey) (device, map[string]any, map[string]any) {
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	idb := make([]byte, 8)
	_, _ = rand.Read(idb)
	id := hex.EncodeToString(idb)
	cert := ed25519.Sign(aik, []byte("CHAT-DEVICE-V1\n"+name+"@"+n.domain+"\n"+id+"\n"+hex.EncodeToString(pub)))
	mb := make([]byte, 16)
	_, _ = rand.Read(mb)
	mbID := strings.ToLower(base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString(mb))
	th := sha256.Sum256([]byte("inbox-token-" + id))
	return device{id, pub, priv},
		map[string]any{"id": id, "dpk": b64.EncodeToString(pub), "cert": b64.EncodeToString(cert)},
		map[string]any{"mailbox_id": mbID, "token_hash": b64.EncodeToString(th[:])}
}

func (n *node) invite() string {
	req, _ := http.NewRequest("POST", n.ts.URL+"/v1/admin/invites", nil)
	req.Header.Set("X-Admin-Key", n.cfg.AdminKey)
	resp := do(n.t, req)
	var out struct{ Invite string }
	mustJSON(n.t, resp, 201, &out)
	return out.Invite
}

func do(t *testing.T, req *http.Request) *http.Response {
	t.Helper()
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	return resp
}

func mustJSON(t *testing.T, resp *http.Response, code int, v any) {
	t.Helper()
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != code {
		t.Fatalf("status %d (want %d): %s", resp.StatusCode, code, b)
	}
	if v != nil {
		if err := json.Unmarshal(b, v); err != nil {
			t.Fatalf("json: %v: %s", err, b)
		}
	}
}

func (n *node) register(name, invite string) (*user, *http.Response) {
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	ts := time.Now().Unix()
	msg := "CHAT-REGISTER-V1\n" + n.domain + "\n" + name + "\n" + strconv.FormatInt(ts, 10)
	dev, devJSON, inboxJSON := n.newDevice(name, priv)
	body, _ := json.Marshal(map[string]any{
		"invite": invite, "name": name, "ik": b64.EncodeToString(pub), "ts": ts,
		"sig":         b64.EncodeToString(ed25519.Sign(priv, []byte(msg))),
		"keypackages": []string{b64.EncodeToString([]byte("kp1")), b64.EncodeToString([]byte("kp2"))},
		"last_resort": b64.EncodeToString([]byte("lastresort")),
		"device":      devJSON, "inbox": inboxJSON,
	})
	req, _ := http.NewRequest("POST", n.ts.URL+"/v1/register", bytes.NewReader(body))
	resp := do(n.t, req)
	u := &user{n: n, name: name, pub: pub, priv: priv, dev: dev}
	return u, resp
}

func (n *node) mustUser(name string) *user {
	u, resp := n.register(name, n.invite())
	var out struct {
		Intro struct {
			MailboxID string `json:"mailbox_id"`
			SendToken string `json:"send_token"`
		}
	}
	mustJSON(n.t, resp, 201, &out)
	u.intro.ID, u.intro.Token = out.Intro.MailboxID, out.Intro.SendToken
	return u
}

func (u *user) sign(method, uri string, body []byte, nonce string, ts int64) string {
	sum := sha256.Sum256(body)
	t := strconv.FormatInt(ts, 10)
	msg := "CHAT-REQ-V1\n" + u.n.domain + "\n" + method + "\n" + uri + "\n" + t + "\n" + nonce + "\n" + hex.EncodeToString(sum[:])
	sig := ed25519.Sign(u.dev.priv, []byte(msg))
	return "Chat-Sig name=" + u.name + ",dev=" + u.dev.id + ",ts=" + t + ",nonce=" + nonce + ",sig=" + b64.EncodeToString(sig)
}

func nonce() string { b := make([]byte, 12); _, _ = rand.Read(b); return b64.EncodeToString(b) }

func (u *user) req(method, uri string, body any) *http.Request {
	var b []byte
	switch v := body.(type) {
	case nil:
	case []byte:
		b = v
	default:
		b, _ = json.Marshal(v)
	}
	r, _ := http.NewRequest(method, u.n.ts.URL+uri, bytes.NewReader(b))
	r.Header.Set("Authorization", u.sign(method, uri, b, nonce(), time.Now().Unix()))
	return r
}

func (u *user) call(method, uri string, body any, code int, out any) {
	u.n.t.Helper()
	mustJSON(u.n.t, do(u.n.t, u.req(method, uri, body)), code, out)
}

func (u *user) newMailbox() (id, tok string) {
	var out struct {
		MailboxID string `json:"mailbox_id"`
		SendToken string `json:"send_token"`
	}
	u.call("POST", "/v1/mailboxes", nil, 201, &out)
	return out.MailboxID, out.SendToken
}

func put(n *node, mailbox, token string, data []byte) *http.Response {
	r, _ := http.NewRequest("PUT", n.ts.URL+"/v1/mailboxes/"+mailbox+"/messages", bytes.NewReader(data))
	r.Header.Set("X-Send-Token", token)
	return do(n.t, r)
}

type msg struct {
	Seq       int64  `json:"seq"`
	MailboxID string `json:"mailbox_id"`
	Data      string `json:"data"`
}

func (u *user) messages(after int64) []msg {
	var out []msg
	u.call("GET", "/v1/messages?after="+strconv.FormatInt(after, 10), nil, 200, &out)
	return out
}

func TestRegistrationRules(t *testing.T) {
	n := newNode(t, nil)
	if _, r := n.register("alice", ""); r.StatusCode != 403 {
		t.Fatalf("no invite: %d", r.StatusCode)
	}
	inv := n.invite()
	if _, r := n.register("alice", inv); r.StatusCode != 201 {
		t.Fatalf("register: %d", r.StatusCode)
	}
	if _, r := n.register("bob", inv); r.StatusCode != 403 { // einmalig
		t.Fatalf("reused invite: %d", r.StatusCode)
	}
	if _, r := n.register("alice", n.invite()); r.StatusCode != 409 {
		t.Fatalf("dup name: %d", r.StatusCode)
	}
	if _, r := n.register("A!", n.invite()); r.StatusCode != 400 {
		t.Fatalf("bad name: %d", r.StatusCode)
	}
	// Admin-Schlüssel nötig
	req, _ := http.NewRequest("POST", n.ts.URL+"/v1/admin/invites", nil)
	if r := do(t, req); r.StatusCode != 401 {
		t.Fatalf("admin: %d", r.StatusCode)
	}
}

func TestRegistrationClosedAndOpenPoW(t *testing.T) {
	n := newNode(t, func(c *config.Config) { c.Registration = "closed" })
	if _, r := n.register("alice", "x"); r.StatusCode != 403 {
		t.Fatalf("closed: %d", r.StatusCode)
	}
	o := newNode(t, func(c *config.Config) { c.Registration = "open" })
	if _, r := o.register("alice", ""); r.StatusCode != 403 { // PoW fehlt
		t.Fatalf("pow missing: %d", r.StatusCode)
	}
}

func TestOpenRegistrationWithPoW(t *testing.T) {
	n := newNode(t, func(c *config.Config) { c.Registration = "open"; c.RegistrationPoW = 8 })
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	ts := time.Now().Unix()
	tss := strconv.FormatInt(ts, 10)
	reg := func(pow string) int {
		sig := ed25519.Sign(priv, []byte("CHAT-REGISTER-V1\n"+n.domain+"\nalice\n"+tss))
		_, devJSON, inboxJSON := n.newDevice("alice", priv)
		body, _ := json.Marshal(map[string]any{"name": "alice", "ik": b64.EncodeToString(pub), "ts": ts, "sig": b64.EncodeToString(sig), "pow": pow, "device": devJSON, "inbox": inboxJSON})
		req, _ := http.NewRequest("POST", n.ts.URL+"/v1/register", bytes.NewReader(body))
		resp := do(t, req)
		resp.Body.Close()
		return resp.StatusCode
	}
	if c := reg("nope"); c != 403 && c != 201 { // 1/256 Zufallstreffer möglich
		t.Fatalf("bad pow: %d", c)
	}
	for i := 0; ; i++ {
		nonce := strconv.Itoa(i)
		h := sha256.Sum256([]byte("alice:" + tss + ":" + nonce))
		if h[0] == 0 {
			if c := reg(nonce); c != 201 && c != 409 {
				t.Fatalf("valid pow rejected: %d", c)
			}
			return
		}
	}
}

func TestAuthReplayAndSkew(t *testing.T) {
	n := newNode(t, nil)
	u := n.mustUser("alice")
	r := u.req("GET", "/v1/mailboxes", nil)
	if resp := do(t, r); resp.StatusCode != 200 {
		t.Fatalf("first: %d", resp.StatusCode)
	}
	// identischer Request (Replay) → abgelehnt
	r2, _ := http.NewRequest("GET", n.ts.URL+"/v1/mailboxes", nil)
	r2.Header.Set("Authorization", r.Header.Get("Authorization"))
	if resp := do(t, r2); resp.StatusCode != 401 {
		t.Fatalf("replay: %d", resp.StatusCode)
	}
	// alter Zeitstempel
	r3, _ := http.NewRequest("GET", n.ts.URL+"/v1/mailboxes", nil)
	r3.Header.Set("Authorization", u.sign("GET", "/v1/mailboxes", nil, nonce(), time.Now().Add(-10*time.Minute).Unix()))
	if resp := do(t, r3); resp.StatusCode != 401 {
		t.Fatalf("skew: %d", resp.StatusCode)
	}
	// Body manipuliert → Signatur ungültig
	r4 := u.req("POST", "/v1/mailboxes", []byte(`{}`))
	r4.Body = io.NopCloser(strings.NewReader(`{"x":1}`))
	r4.ContentLength = 7
	if resp := do(t, r4); resp.StatusCode != 401 {
		t.Fatalf("tamper: %d", resp.StatusCode)
	}
	// anderer Nutzer kann nicht im Namen von alice signieren
	b := n.mustUser("bob")
	b.name = "alice"
	if resp := do(t, b.req("GET", "/v1/mailboxes", nil)); resp.StatusCode != 401 {
		t.Fatalf("impersonation: %d", resp.StatusCode)
	}
}

func TestMailboxLifecycleAndBlocking(t *testing.T) {
	n := newNode(t, nil)
	alice := n.mustUser("alice")
	id, tok := alice.newMailbox()

	if r := put(n, id, "wrong", []byte("hi")); r.StatusCode != 404 {
		t.Fatalf("wrong token: %d", r.StatusCode)
	}
	if r := put(n, id, tok, []byte("hello")); r.StatusCode != 202 {
		t.Fatalf("put: %d", r.StatusCode)
	}
	if r := put(n, id, tok, make([]byte, 65<<10)); r.StatusCode != 413 {
		t.Fatalf("too large: %d", r.StatusCode)
	}
	if r := put(n, id, tok, nil); r.StatusCode != 413 {
		t.Fatalf("empty: %d", r.StatusCode)
	}
	msgs := alice.messages(0)
	if len(msgs) != 1 || msgs[0].MailboxID != id {
		t.Fatalf("msgs: %+v", msgs)
	}
	if d, _ := b64.DecodeString(msgs[0].Data); string(d) != "hello" {
		t.Fatal("data")
	}
	alice.call("DELETE", "/v1/messages?upto="+strconv.FormatInt(msgs[0].Seq, 10), nil, 204, nil)
	if len(alice.messages(0)) != 0 {
		t.Fatal("ack failed")
	}
	// Postfach widerrufen = Absender ist blockiert
	alice.call("DELETE", "/v1/mailboxes/"+id, nil, 204, nil)
	if r := put(n, id, tok, []byte("again")); r.StatusCode != 404 {
		t.Fatalf("revoked: %d", r.StatusCode)
	}
	// Fremde dürfen nicht löschen
	bob := n.mustUser("bob")
	bob.call("DELETE", "/v1/mailboxes/"+n.introID(alice), nil, 404, nil)
}

func (n *node) introID(u *user) string { return u.intro.ID }

func TestKeyPackages(t *testing.T) {
	n := newNode(t, nil)
	alice := n.mustUser("alice")
	type dk struct{ Device, Keypackage string }
	get := func() (kps []string, code int) {
		resp, err := http.Get(n.ts.URL + "/v1/users/alice/keypackages")
		if err != nil {
			t.Fatal(err)
		}
		var out struct {
			IK      string
			Devices []dk
		}
		defer resp.Body.Close()
		_ = json.NewDecoder(resp.Body).Decode(&out)
		for _, d := range out.Devices {
			if d.Device != alice.dev.id {
				t.Fatalf("unexpected device %s", d.Device)
			}
			b, _ := b64.DecodeString(d.Keypackage)
			kps = append(kps, string(b))
		}
		return kps, resp.StatusCode
	}
	for _, want := range []string{"kp1", "kp2", "lastresort", "lastresort"} {
		if got, c := get(); c != 200 || len(got) != 1 || got[0] != want {
			t.Fatalf("got %q (%d) want %q", got, c, want)
		}
	}
	var cnt struct{ Count int }
	alice.call("GET", "/v1/keypackages/count", nil, 200, &cnt)
	if cnt.Count != 0 {
		t.Fatalf("count %d", cnt.Count)
	}
	kps := []string{}
	for i := 0; i < 6; i++ {
		kps = append(kps, b64.EncodeToString([]byte{byte(i), 1}))
	}
	alice.call("PUT", "/v1/keypackages", map[string]any{"keypackages": kps}, 400, nil) // > MaxKeyPackages
	alice.call("PUT", "/v1/keypackages", map[string]any{"keypackages": kps[:3]}, 204, nil)
	if r, _ := http.Get(n.ts.URL + "/v1/users/nobody/keypackages"); r.StatusCode != 404 {
		t.Fatal("unknown user")
	}
}

func TestBlobsQuotaAndLimits(t *testing.T) {
	n := newNode(t, nil)
	alice := n.mustUser("alice")
	up := func(data []byte) (*http.Response, string) {
		uri := "/v1/blobs"
		t0 := time.Now().Unix()
		nc := nonce()
		m := "CHAT-REQ-V1\n" + n.domain + "\nPOST\n" + uri + "\n" + strconv.FormatInt(t0, 10) + "\n" + nc + "\nUNSIGNED"
		sig := b64.EncodeToString(ed25519.Sign(alice.dev.priv, []byte(m)))
		r, _ := http.NewRequest("POST", n.ts.URL+uri, bytes.NewReader(data))
		r.ContentLength = int64(len(data))
		r.Header.Set("X-Body-Hash", "UNSIGNED")
		r.Header.Set("Authorization", "Chat-Sig name=alice,dev="+alice.dev.id+",ts="+strconv.FormatInt(t0, 10)+",nonce="+nc+",sig="+sig)
		resp := do(t, r)
		var out struct {
			BlobID string `json:"blob_id"`
		}
		if resp.StatusCode == 201 {
			_ = json.NewDecoder(resp.Body).Decode(&out)
		}
		resp.Body.Close()
		return resp, out.BlobID
	}
	data := bytes.Repeat([]byte("ab"), 1000)
	resp, id := up(data)
	if resp.StatusCode != 201 {
		t.Fatalf("upload: %d", resp.StatusCode)
	}
	g, _ := http.Get(n.ts.URL + "/v1/blobs/" + id)
	got, _ := io.ReadAll(g.Body)
	if !bytes.Equal(got, data) || g.Header.Get("Content-Disposition") != "attachment" {
		t.Fatal("download mismatch")
	}
	rr, _ := http.NewRequest("GET", n.ts.URL+"/v1/blobs/"+id, nil)
	rr.Header.Set("Range", "bytes=0-3")
	rg := do(t, rr)
	part, _ := io.ReadAll(rg.Body)
	if rg.StatusCode != 206 || string(part) != "abab" {
		t.Fatalf("range %d %q", rg.StatusCode, part)
	}
	if r, _ := up(make([]byte, 2<<20)); r.StatusCode != 413 { // > MaxFileSize
		t.Fatalf("file limit: %d", r.StatusCode)
	}
	// Quota 3 MiB: weitere 1 MiB-Dateien bis voll
	var code int
	for i := 0; i < 4; i++ {
		r, _ := up(make([]byte, 1<<20))
		code = r.StatusCode
	}
	if code != 507 {
		t.Fatalf("quota: %d", code)
	}
	var q struct{ Used, Quota int64 }
	alice.call("GET", "/v1/quota", nil, 200, &q)
	if q.Quota != 3<<20 || q.Used <= 0 {
		t.Fatalf("quota info %+v", q)
	}
	alice.call("DELETE", "/v1/blobs/"+id, nil, 204, nil)
	if g, _ := http.Get(n.ts.URL + "/v1/blobs/" + id); g.StatusCode != 404 {
		t.Fatal("deleted blob still served")
	}
	if g, _ := http.Get(n.ts.URL + "/v1/blobs/..%2f..%2fetc"); g.StatusCode != 404 {
		t.Fatal("traversal")
	}
}

func TestFederationRelay(t *testing.T) {
	a := newNode(t, nil)
	b := newNode(t, nil)
	alice := a.mustUser("alice")
	bob := b.mustUser("bob")
	mb, tok := bob.newMailbox()

	relay := func(domain, mailbox, token string, data string) int {
		r := alice.req("POST", "/v1/relay", map[string]any{
			"domain": domain, "mailbox_id": mailbox, "send_token": token, "data": b64.EncodeToString([]byte(data)),
		})
		resp := do(t, r)
		resp.Body.Close()
		return resp.StatusCode
	}
	if c := relay(b.domain, mb, tok, "cross-server"); c != 202 {
		t.Fatalf("relay: %d", c)
	}
	m := bob.messages(0)
	if len(m) != 1 {
		t.Fatalf("not delivered: %+v", m)
	}
	if d, _ := b64.DecodeString(m[0].Data); string(d) != "cross-server" {
		t.Fatal("payload")
	}
	if c := relay(b.domain, mb, "wrong", "x"); c != 404 {
		t.Fatalf("wrong token over fed: %d", c)
	}
	// KeyPackage über Home-Server auflösen
	var kp struct {
		Devices []struct{ Device, Keypackage string }
	}
	alice.call("GET", "/v1/resolve/bob@"+b.domain+"/keypackages", nil, 200, &kp)
	if len(kp.Devices) != 1 {
		t.Fatalf("resolve: %+v", kp)
	}
	if d, _ := b64.DecodeString(kp.Devices[0].Keypackage); string(d) != "kp1" {
		t.Fatalf("resolve: %q", d)
	}

	// Server-Filter des Empfängers: Domain von A blockieren → still verworfen (202, aber nicht zugestellt)
	h := sha256.Sum256([]byte(a.domain))
	bob.call("PUT", "/v1/filters", map[string]any{"mode": "block", "domains": []string{b64.EncodeToString(h[:])}}, 204, nil)
	if c := relay(b.domain, mb, tok, "blocked"); c != 202 {
		t.Fatalf("blocked relay should look like success: %d", c)
	}
	if len(bob.messages(m[0].Seq)) != 0 {
		t.Fatal("blocked message was delivered")
	}
	// Allowlist-Modus ohne Eintrag → ebenfalls verworfen
	bob.call("PUT", "/v1/filters", map[string]any{"mode": "allow", "domains": []string{}}, 204, nil)
	relay(b.domain, mb, tok, "not-allowed")
	if len(bob.messages(m[0].Seq)) != 0 {
		t.Fatal("allowlist violated")
	}
	bob.call("PUT", "/v1/filters", map[string]any{"mode": "allow", "domains": []string{b64.EncodeToString(h[:])}}, 204, nil)
	relay(b.domain, mb, tok, "allowed")
	if len(bob.messages(m[0].Seq)) != 1 {
		t.Fatal("allowlisted origin not delivered")
	}
}

func TestFederationOperatorPolicy(t *testing.T) {
	b := newNode(t, nil)
	a := newNode(t, func(c *config.Config) { c.FedBlock = []string{b.domain} })
	alice := a.mustUser("alice")
	bob := b.mustUser("bob")
	mb, tok := bob.newMailbox()
	r := alice.req("POST", "/v1/relay", map[string]any{"domain": b.domain, "mailbox_id": mb, "send_token": tok, "data": b64.EncodeToString([]byte("x"))})
	if resp := do(t, r); resp.StatusCode != 403 {
		t.Fatalf("outbound blocked expected 403, got %d", resp.StatusCode)
	}
	// Eingehend: B im Allowlist-Modus ohne A
	c := newNode(t, func(c *config.Config) { c.Federation = "allowlist" })
	carol := c.mustUser("carol")
	cmb, ctok := carol.newMailbox()
	b2 := b.mustUser("bob2")
	r = b2.req("POST", "/v1/relay", map[string]any{"domain": c.domain, "mailbox_id": cmb, "send_token": ctok, "data": b64.EncodeToString([]byte("x"))})
	if resp := do(t, r); resp.StatusCode != 403 {
		t.Fatalf("inbound allowlist expected 403, got %d", resp.StatusCode)
	}
	if len(carol.messages(0)) != 0 {
		t.Fatal("delivered despite allowlist")
	}
}

func TestFederationRejectsForgery(t *testing.T) {
	a := newNode(t, nil)
	b := newNode(t, nil)
	bob := b.mustUser("bob")
	mb, tok := bob.newMailbox()
	_ = a
	body, _ := json.Marshal(map[string]string{"mailbox_id": mb, "send_token": tok, "data": b64.EncodeToString([]byte("forged"))})
	ts := strconv.FormatInt(time.Now().Unix(), 10)
	_, wrongKey, _ := ed25519.GenerateKey(rand.Reader)
	sum := sha256.Sum256(body)
	sig := ed25519.Sign(wrongKey, []byte("CHAT-FED-V1\n"+b.domain+"\n"+ts+"\n"+hex.EncodeToString(sum[:])))
	r, _ := http.NewRequest("POST", b.ts.URL+"/v1/federation/deliver", bytes.NewReader(body))
	r.Header.Set("X-Chat-Origin", a.domain) // behauptet, A zu sein
	r.Header.Set("X-Chat-Ts", ts)
	r.Header.Set("X-Chat-Sig", b64.EncodeToString(sig))
	if resp := do(t, r); resp.StatusCode != 401 {
		t.Fatalf("forged origin: %d", resp.StatusCode)
	}
	if len(bob.messages(0)) != 0 {
		t.Fatal("forged message delivered")
	}
}

func TestSSRFProtection(t *testing.T) {
	a := newNode(t, func(c *config.Config) { c.FedInsecure = false })
	alice := a.mustUser("alice")
	r := alice.req("POST", "/v1/relay", map[string]any{"domain": "127.0.0.1:1", "mailbox_id": "x", "send_token": "y", "data": b64.EncodeToString([]byte("x"))})
	if resp := do(t, r); resp.StatusCode != 502 {
		t.Fatalf("loopback target should fail with 502, got %d", resp.StatusCode)
	}
}

func TestWebSocketPush(t *testing.T) {
	n := newNode(t, nil)
	alice := n.mustUser("alice")
	id, tok := alice.newMailbox()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	c, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(n.ts.URL, "http")+"/v1/stream", nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c.CloseNow()
	nc := nonce()
	ts := time.Now().Unix()
	m := "CHAT-REQ-V1\n" + n.domain + "\nGET\n/v1/stream\n" + strconv.FormatInt(ts, 10) + "\n" + nc + "\nWS"
	auth, _ := json.Marshal(map[string]string{"name": "alice", "dev": alice.dev.id, "ts": strconv.FormatInt(ts, 10), "nonce": nc,
		"sig": b64.EncodeToString(ed25519.Sign(alice.dev.priv, []byte(m)))})
	if err := c.Write(ctx, websocket.MessageText, auth); err != nil {
		t.Fatal(err)
	}
	_, b, err := c.Read(ctx)
	if err != nil || !strings.Contains(string(b), "ready") {
		t.Fatalf("ready: %s %v", b, err)
	}
	put(n, id, tok, []byte("pushed")).Body.Close()
	_, b, err = c.Read(ctx)
	if err != nil || !strings.Contains(string(b), `"message"`) {
		t.Fatalf("push: %s %v", b, err)
	}
	// ungültige Auth wird abgelehnt
	c2, _, _ := websocket.Dial(ctx, "ws"+strings.TrimPrefix(n.ts.URL, "http")+"/v1/stream", nil)
	defer c2.CloseNow()
	_ = c2.Write(ctx, websocket.MessageText, []byte(`{"name":"alice","dev":"0123456789abcdef","ts":"1","nonce":"12345678","sig":"AAAA"}`))
	if _, _, err := c2.Read(ctx); err == nil {
		t.Fatal("unauthenticated ws accepted")
	}
}

func TestServerInfoAndBootstrap(t *testing.T) {
	n := newNode(t, nil)
	resp, _ := http.Get(n.ts.URL + "/v1/server-info")
	var info struct {
		Domain string
		Limits map[string]any
	}
	mustJSON(t, resp, 200, &info)
	if info.Domain != n.domain || info.Limits["max_file_size"].(float64) != float64(1<<20) {
		t.Fatalf("info %+v", info)
	}
	tok, err := n.srv.BootstrapInvite()
	if err != nil || tok == "" {
		t.Fatalf("bootstrap: %q %v", tok, err)
	}
	if _, r := n.register("first", tok); r.StatusCode != 201 {
		t.Fatalf("bootstrap invite: %d", r.StatusCode)
	}
	if tok, _ := n.srv.BootstrapInvite(); tok != "" {
		t.Fatal("bootstrap invite issued although users exist")
	}
}

func TestUserInvites(t *testing.T) {
	n := newNode(t, nil)
	alice := n.mustUser("alice")
	var out struct{ Invite string }
	alice.call("POST", "/v1/invites", nil, 201, &out)
	if _, r := n.register("bob", out.Invite); r.StatusCode != 201 {
		t.Fatalf("user invite: %d", r.StatusCode)
	}
	n.cfg.UserInvites = false
	alice.call("POST", "/v1/invites", nil, 403, nil)
}

// addDevice: zweites Gerät, beglaubigt durch den Konto-Schlüssel (kein Altgerät nötig).
func (u *user) addDevice(sigKey ed25519.PrivateKey, ts int64) (*user, *http.Response) {
	dev, devJSON, inboxJSON := u.n.newDevice(u.name, u.priv)
	t := strconv.FormatInt(ts, 10)
	msg := "CHAT-ADD-DEVICE-V1\n" + u.n.domain + "\n" + u.name + "\n" + t + "\n" + dev.id
	body, _ := json.Marshal(map[string]any{
		"name": u.name, "ts": ts, "sig": b64.EncodeToString(ed25519.Sign(sigKey, []byte(msg))),
		"device": devJSON, "inbox": inboxJSON,
		"keypackages": []string{b64.EncodeToString([]byte("kp-dev2"))}, "last_resort": b64.EncodeToString([]byte("last-dev2")),
	})
	r, _ := http.NewRequest("POST", u.n.ts.URL+"/v1/devices", bytes.NewReader(body))
	resp := do(u.n.t, r)
	d2 := *u
	d2.dev = dev
	d2.inboxID = inboxJSON["mailbox_id"].(string)
	return &d2, resp
}

func TestMultiDevice(t *testing.T) {
	n := newNode(t, func(c *config.Config) { c.MaxDevices = 3 })
	d1 := n.mustUser("alice")
	// falsche Beglaubigung (nicht vom Konto-Schlüssel) → abgelehnt
	_, evilPriv, _ := ed25519.GenerateKey(rand.Reader)
	if _, r := d1.addDevice(evilPriv, time.Now().Unix()); r.StatusCode != 401 {
		t.Fatalf("add with foreign key: %d", r.StatusCode)
	}
	// alter Zeitstempel
	if _, r := d1.addDevice(d1.priv, time.Now().Add(-10*time.Minute).Unix()); r.StatusCode != 400 {
		t.Fatalf("add with old ts: %d", r.StatusCode)
	}
	d2, r := d1.addDevice(d1.priv, time.Now().Unix())
	if r.StatusCode != 201 {
		t.Fatalf("add device: %d", r.StatusCode)
	}

	var devs []struct {
		ID      string
		Current bool
	}
	d1.call("GET", "/v1/devices", nil, 200, &devs)
	if len(devs) != 2 {
		t.Fatalf("devices: %+v", devs)
	}
	// Zertifikat von einem fremden Konto-Schlüssel wird abgelehnt (anderes Konto)
	bob := n.mustUser("bob")
	_, devJSON, inboxJSON := n.newDevice("alice", bob.priv) // „alice“-Gerät, beglaubigt von Bobs AIK
	ts := time.Now().Unix()
	msg := "CHAT-ADD-DEVICE-V1\n" + n.domain + "\nalice\n" + strconv.FormatInt(ts, 10) + "\n" + devJSON["id"].(string)
	body, _ := json.Marshal(map[string]any{"name": "alice", "ts": ts, "sig": b64.EncodeToString(ed25519.Sign(d1.priv, []byte(msg))), "device": devJSON, "inbox": inboxJSON})
	rq, _ := http.NewRequest("POST", n.ts.URL+"/v1/devices", bytes.NewReader(body))
	if resp := do(t, rq); resp.StatusCode != 400 {
		t.Fatalf("bad cert: %d", resp.StatusCode)
	}

	// Unterhaltungs-Postfächer gehören dem Gerät, das sie angelegt hat
	id0, tok0 := d1.newMailbox()
	put(n, id0, tok0, []byte("nur geraet 1")).Body.Close()
	if len(d2.messages(0)) != 0 || len(d1.messages(0)) != 1 {
		t.Fatal("conversation mailbox must be device-level")
	}
	d1.call("DELETE", "/v1/messages?upto="+strconv.FormatInt(d1.messages(0)[0].Seq, 10), nil, 204, nil)
	// Gerät 2 darf das Postfach von Gerät 1 nicht löschen
	d2.call("DELETE", "/v1/mailboxes/"+id0, nil, 404, nil)

	// Kontoweites (Intro-)Postfach: jedes Gerät bekommt eine eigene Kopie, Acks sind unabhängig
	put(n, d1.intro.ID, d1.intro.Token, []byte("an alle geraete")).Body.Close()
	m1, m2 := d1.messages(0), d2.messages(0)
	if len(m1) != 1 || len(m2) != 1 {
		t.Fatalf("fan-out: %d / %d", len(m1), len(m2))
	}
	d1.call("DELETE", "/v1/messages?upto="+strconv.FormatInt(m1[0].Seq, 10), nil, 204, nil)
	if len(d1.messages(0)) != 0 || len(d2.messages(0)) != 1 {
		t.Fatal("ack must be per device")
	}

	// Geräte-Postfach: nur dieses Gerät
	put(n, d2.inboxID, "inbox-token-"+d2.dev.id, []byte("nur geraet 2")).Body.Close()
	if len(d1.messages(0)) != 0 {
		t.Fatal("device inbox leaked to other device")
	}
	if got := d2.messages(0); len(got) != 2 {
		t.Fatalf("device inbox: %d", len(got))
	}

	// KeyPackages: eines je Gerät
	resp, _ := http.Get(n.ts.URL + "/v1/users/alice/keypackages")
	var kps struct{ Devices []struct{ Device string } }
	mustJSON(t, resp, 200, &kps)
	if len(kps.Devices) != 2 {
		t.Fatalf("keypackages per device: %+v", kps)
	}

	// Gerätelimit (3): ein drittes geht, ein viertes nicht
	if _, r := d1.addDevice(d1.priv, time.Now().Unix()); r.StatusCode != 201 {
		t.Fatalf("third device: %d", r.StatusCode)
	}
	if _, r := d1.addDevice(d1.priv, time.Now().Unix()); r.StatusCode != 429 {
		t.Fatalf("limit: %d", r.StatusCode)
	}

	// Widerruf: Gerät 2 kann sich nicht mehr anmelden, sein Postfach ist weg
	d1.call("DELETE", "/v1/devices/"+d2.dev.id, nil, 204, nil)
	if resp := do(t, d2.req("GET", "/v1/mailboxes", nil)); resp.StatusCode != 401 {
		t.Fatalf("revoked device still authenticated: %d", resp.StatusCode)
	}
	if r := put(n, d2.inboxID, "inbox-token-"+d2.dev.id, []byte("x")); r.StatusCode != 404 {
		t.Fatalf("revoked inbox: %d", r.StatusCode)
	}
	d1.call("DELETE", "/v1/devices/"+d2.dev.id, nil, 404, nil)
}

func TestCannotRevokeLastDevice(t *testing.T) {
	n := newNode(t, nil)
	a := n.mustUser("alice")
	a.call("DELETE", "/v1/devices/"+a.dev.id, nil, 409, nil)
}

func TestWebSocketDeviceEvents(t *testing.T) {
	n := newNode(t, nil)
	d1 := n.mustUser("alice")
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	c, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(n.ts.URL, "http")+"/v1/stream", nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c.CloseNow()
	nc := nonce()
	ts := time.Now().Unix()
	m := "CHAT-REQ-V1\n" + n.domain + "\nGET\n/v1/stream\n" + strconv.FormatInt(ts, 10) + "\n" + nc + "\nWS"
	auth, _ := json.Marshal(map[string]string{"name": "alice", "dev": d1.dev.id, "ts": strconv.FormatInt(ts, 10), "nonce": nc,
		"sig": b64.EncodeToString(ed25519.Sign(d1.dev.priv, []byte(m)))})
	_ = c.Write(ctx, websocket.MessageText, auth)
	if _, b, err := c.Read(ctx); err != nil || !strings.Contains(string(b), "ready") {
		t.Fatalf("ready: %s %v", b, err)
	}
	d2, r := d1.addDevice(d1.priv, time.Now().Unix())
	if r.StatusCode != 201 {
		t.Fatal(r.StatusCode)
	}
	if _, b, err := c.Read(ctx); err != nil || !strings.Contains(string(b), `"devices"`) {
		t.Fatalf("device event: %s %v", b, err)
	}
	// Widerruf trennt die Verbindung des Geräts: Gerät 1 widerruft sich selbst nicht; wir widerrufen Gerät 2 und erwarten ein weiteres Ereignis
	d1.call("DELETE", "/v1/devices/"+d2.dev.id, nil, 204, nil)
	if _, b, err := c.Read(ctx); err != nil || !strings.Contains(string(b), `"devices"`) {
		t.Fatalf("revoke event: %s %v", b, err)
	}
}
