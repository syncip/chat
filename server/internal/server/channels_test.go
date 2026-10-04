package server_test

import (
	"bytes"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"math/bits"
	"net/http"
	"strconv"
	"testing"

	"github.com/syncip/chat/server/internal/config"
	"time"
)

func (u *user) chanReq(method, uri string, body any) *http.Request {
	var b []byte
	if body != nil {
		b, _ = json.Marshal(body)
	}
	ts, nc := strconv.FormatInt(time.Now().Unix(), 10), nonce()
	sum := sha256.Sum256(b)
	msg := "CHAT-CHAN-V1\n" + u.n.domain + "\n" + method + "\n" + uri + "\n" + ts + "\n" + nc + "\n" + hex.EncodeToString(sum[:])
	r, _ := http.NewRequest(method, u.n.ts.URL+uri, bytes.NewReader(b))
	r.Header.Set("Authorization", "Chan-Sig ik="+b64.EncodeToString(u.pub)+",ts="+ts+",nonce="+nc+",sig="+b64.EncodeToString(ed25519.Sign(u.priv, []byte(msg))))
	return r
}

func (u *user) chanCall(method, uri string, body any, code int, out any) {
	u.n.t.Helper()
	mustJSON(u.n.t, do(u.n.t, u.chanReq(method, uri, body)), code, out)
}

func (u *user) addr() string { return u.name + "@" + u.n.domain }

func (u *user) post(ch, text string, code int) {
	u.n.t.Helper()
	data := []byte("ciphertext-" + text)
	id := "p" + text + "-000000"
	ts := time.Now().UnixMilli()
	h := sha256.Sum256(data)
	msg := "CHAT-POST-V1\n" + ch + "\n" + id + "\n" + strconv.FormatInt(ts, 10) + "\n0\n" + hex.EncodeToString(h[:])
	u.chanCall("POST", "/v1/channels/"+ch+"/posts", map[string]any{
		"post_id": id, "ts": ts, "epoch": 0, "data": b64.EncodeToString(data), "sig": b64.EncodeToString(ed25519.Sign(u.priv, []byte(msg))),
	}, code, nil)
}

func (u *user) entries(ch string) []map[string]any {
	var out struct{ Entries []map[string]any }
	u.chanCall("GET", "/v1/channels/"+ch+"/log?after=0", nil, 200, &out)
	return out.Entries
}

func countPosts(es []map[string]any) (n int) {
	for _, e := range es {
		if e["type"] == "post" && e["deleted"] != true {
			n++
		}
	}
	return
}

func newChannel(owner *user, policy map[string]any) string {
	var out struct{ ID string }
	owner.call("POST", "/v1/channels", map[string]any{"title_enc": b64.EncodeToString([]byte("title-ct")), "policy": policy}, 201, &out)
	return out.ID
}

func TestChannelsOpenRightsAndModeration(t *testing.T) {
	n := newNode(t, nil)
	owner, mod, alice, bob := n.mustUser("owner"), n.mustUser("mod"), n.mustUser("alice"), n.mustUser("bob")
	ch := newChannel(owner, map[string]any{"join_mode": "open", "members_can_write": true})
	join := func(u *user) {
		u.chanCall("POST", "/v1/channels/"+ch+"/join", map[string]any{"address": u.addr()}, 200, nil)
	}
	for _, u := range []*user{mod, alice, bob} {
		join(u)
	}
	// falsche Adresse wird abgelehnt
	n.mustUser("eve").chanCall("POST", "/v1/channels/"+ch+"/join", map[string]any{"address": "alice@" + n.domain}, 400, nil)

	var info struct {
		Members int
		Policy  map[string]any
	}
	mustJSON(t, do(t, mustReq("GET", n.ts.URL+"/v1/channels/"+ch)), 200, &info)
	if info.Members != 4 || info.Policy["join_mode"] != "open" {
		t.Fatalf("info %+v", info)
	}

	alice.post(ch, "hi", 201)
	alice.post(ch, "hi", 409) // doppelte Post-ID
	owner.chanCall("POST", "/v1/channels/"+ch+"/mod", map[string]any{"action": "role", "target": b64.EncodeToString(mod.pub), "role": "mod"}, 200, nil)
	// Moderator darf Mitglieder nicht zu Moderatoren machen, darf aber löschen/sperren
	mod.chanCall("POST", "/v1/channels/"+ch+"/mod", map[string]any{"action": "role", "target": b64.EncodeToString(bob.pub), "role": "mod"}, 403, nil)
	bob.chanCall("POST", "/v1/channels/"+ch+"/mod", map[string]any{"action": "ban", "target": b64.EncodeToString(alice.pub)}, 403, nil)

	// individuell: nur lesen
	mod.chanCall("POST", "/v1/channels/"+ch+"/mod", map[string]any{"action": "role", "target": b64.EncodeToString(bob.pub), "role": "read"}, 200, nil)
	bob.post(ch, "nope", 403)
	// Timeout
	mod.chanCall("POST", "/v1/channels/"+ch+"/mod", map[string]any{"action": "timeout", "target": b64.EncodeToString(alice.pub), "seconds": 3600}, 200, nil)
	alice.post(ch, "muted", 403)
	mod.chanCall("POST", "/v1/channels/"+ch+"/mod", map[string]any{"action": "timeout", "target": b64.EncodeToString(alice.pub), "seconds": 0}, 200, nil)
	alice.post(ch, "back", 201)
	// Löschen
	mod.chanCall("POST", "/v1/channels/"+ch+"/mod", map[string]any{"action": "delete", "post_id": "phi-000000"}, 200, nil)
	if got := countPosts(bob.entries(ch)); got != 1 {
		t.Fatalf("posts after delete = %d", got)
	}
	// Moderator kann Besitzer nicht sperren
	mod.chanCall("POST", "/v1/channels/"+ch+"/mod", map[string]any{"action": "ban", "target": b64.EncodeToString(owner.pub)}, 403, nil)
	// Bann: weder lesen noch schreiben noch erneut beitreten
	mod.chanCall("POST", "/v1/channels/"+ch+"/mod", map[string]any{"action": "ban", "target": b64.EncodeToString(alice.pub)}, 200, nil)
	alice.chanCall("GET", "/v1/channels/"+ch+"/log?after=0", nil, 403, nil)
	alice.post(ch, "banned", 403)
	alice.chanCall("POST", "/v1/channels/"+ch+"/join", map[string]any{"address": alice.addr()}, 403, nil)
	// Globale Regel: nur lesen für alle (Rolle write darf trotzdem)
	owner.chanCall("PUT", "/v1/channels/"+ch+"/settings", map[string]any{"policy": map[string]any{"join_mode": "open", "members_can_write": false}}, 200, nil)
	carol := n.mustUser("carol")
	carol.chanCall("POST", "/v1/channels/"+ch+"/join", map[string]any{"address": carol.addr()}, 200, nil)
	carol.post(ch, "ro", 403)
	owner.chanCall("POST", "/v1/channels/"+ch+"/mod", map[string]any{"action": "role", "target": b64.EncodeToString(carol.pub), "role": "write"}, 200, nil)
	carol.post(ch, "rw", 201)
	owner.post(ch, "ownerpost", 201)
	// Mitgliederliste nur für Moderation
	carol.chanCall("GET", "/v1/channels/"+ch+"/members", nil, 403, nil)
	owner.chanCall("GET", "/v1/channels/"+ch+"/members", nil, 200, nil)
	// Einstellungen nur Besitzer
	mod.chanCall("PUT", "/v1/channels/"+ch+"/settings", map[string]any{"policy": map[string]any{"join_mode": "open"}}, 403, nil)
	// Verlassen und Löschen
	bob.chanCall("POST", "/v1/channels/"+ch+"/leave", nil, 200, nil)
	bob.chanCall("GET", "/v1/channels/"+ch+"/log", nil, 403, nil)
	owner.chanCall("DELETE", "/v1/channels/"+ch, nil, 200, nil)
	mustJSON(t, do(t, mustReq("GET", n.ts.URL+"/v1/channels/"+ch)), 404, nil)
}

func mustReq(method, url string) *http.Request { r, _ := http.NewRequest(method, url, nil); return r }

func TestChannelApprovalProbationSlowMode(t *testing.T) {
	n := newNode(t, nil)
	owner, alice, bob := n.mustUser("owner"), n.mustUser("alice"), n.mustUser("bob")
	ch := newChannel(owner, map[string]any{"join_mode": "approval", "members_can_write": true, "probation_seconds": 3600, "slow_mode_seconds": 60})
	var j struct{ Me map[string]any }
	alice.chanCall("POST", "/v1/channels/"+ch+"/join", map[string]any{"address": alice.addr()}, 200, &j)
	if j.Me["status"] != "pending" {
		t.Fatalf("status %v", j.Me["status"])
	}
	alice.post(ch, "x", 403)
	if got := len(alice.entries(ch)); got != 0 { // Wartende sehen nichts
		t.Fatalf("pending sees %d entries", got)
	}
	owner.chanCall("POST", "/v1/channels/"+ch+"/mod", map[string]any{"action": "approve", "target": b64.EncodeToString(alice.pub)}, 200, nil)
	alice.post(ch, "x", 403) // Sperrfrist
	owner.chanCall("POST", "/v1/channels/"+ch+"/mod", map[string]any{"action": "role", "target": b64.EncodeToString(alice.pub), "role": "write"}, 200, nil)
	alice.post(ch, "one", 201)
	alice.post(ch, "two", 429) // Slow-Mode
	bob.chanCall("POST", "/v1/channels/"+ch+"/join", map[string]any{"address": bob.addr()}, 200, nil)
	owner.chanCall("POST", "/v1/channels/"+ch+"/mod", map[string]any{"action": "reject", "target": b64.EncodeToString(bob.pub)}, 200, nil)
	bob.chanCall("GET", "/v1/channels/"+ch+"/log", nil, 403, nil)
}

func TestChannelPowAndCaptcha(t *testing.T) {
	n := newNode(t, nil)
	owner, alice, bob := n.mustUser("owner"), n.mustUser("alice"), n.mustUser("bob")
	pow := newChannel(owner, map[string]any{"join_mode": "pow", "pow_bits": 10, "members_can_write": true})
	alice.chanCall("POST", "/v1/channels/"+pow+"/join", map[string]any{"address": alice.addr(), "pow_nonce": "0"}, 403, nil)
	var nonceOK string
	for i := 0; ; i++ {
		h := sha256.Sum256([]byte(pow + ":" + b64.EncodeToString(alice.pub) + ":" + strconv.Itoa(i)))
		z := 0
		for _, b := range h {
			if b == 0 {
				z += 8
				continue
			}
			z += bits.LeadingZeros8(b)
			break
		}
		if z >= 10 {
			nonceOK = strconv.Itoa(i)
			break
		}
	}
	alice.chanCall("POST", "/v1/channels/"+pow+"/join", map[string]any{"address": alice.addr(), "pow_nonce": nonceOK}, 200, nil)
	alice.post(pow, "a", 201)

	cap := newChannel(owner, map[string]any{"join_mode": "captcha", "members_can_write": true})
	var c struct{ Token, Image string }
	mustJSON(t, do(t, mustReq("GET", n.ts.URL+"/v1/channels/"+cap+"/captcha?ik="+urlEsc(b64.EncodeToString(bob.pub)))), 200, &c)
	if c.Token == "" || len(c.Image) < 100 {
		t.Fatal("no captcha")
	}
	bob.chanCall("POST", "/v1/channels/"+cap+"/join", map[string]any{"address": bob.addr(), "captcha_token": c.Token, "captcha_answer": "00000x"}, 403, nil)
}

func urlEsc(s string) string {
	r := bytes.NewBuffer(nil)
	for _, c := range []byte(s) {
		switch c {
		case '+':
			r.WriteString("%2B")
		case '/':
			r.WriteString("%2F")
		case '=':
			r.WriteString("%3D")
		default:
			r.WriteByte(c)
		}
	}
	return r.String()
}

func TestChannelLimitsAndDisabled(t *testing.T) {
	n := newNode(t, func(c *config.Config) { c.MaxChannels = 1; c.MaxChannelUsers = 2 })
	owner, a, b := n.mustUser("owner"), n.mustUser("alice"), n.mustUser("bob")
	ch := newChannel(owner, map[string]any{"join_mode": "open"})
	owner.call("POST", "/v1/channels", map[string]any{"title_enc": b64.EncodeToString([]byte("t")), "policy": map[string]any{"join_mode": "open"}}, 429, nil)
	a.chanCall("POST", "/v1/channels/"+ch+"/join", map[string]any{"address": a.addr()}, 200, nil)
	b.chanCall("POST", "/v1/channels/"+ch+"/join", map[string]any{"address": b.addr()}, 429, nil)
	// ungültige Richtlinie
	a.call("POST", "/v1/channels", map[string]any{"title_enc": b64.EncodeToString([]byte("t")), "policy": map[string]any{"join_mode": "bogus"}}, 400, nil)

	off := newNode(t, func(c *config.Config) { c.Channels = false })
	u := off.mustUser("user1")
	u.call("POST", "/v1/channels", map[string]any{"title_enc": b64.EncodeToString([]byte("t")), "policy": map[string]any{"join_mode": "open"}}, 404, nil)
}

func TestChannelAuthorCanDeleteOwnPost(t *testing.T) {
	n := newNode(t, nil)
	owner, alice, bob := n.mustUser("owner"), n.mustUser("alice"), n.mustUser("bob")
	ch := newChannel(owner, map[string]any{"join_mode": "open", "members_can_write": true})
	for _, u := range []*user{alice, bob} {
		u.chanCall("POST", "/v1/channels/"+ch+"/join", map[string]any{"address": u.addr()}, 200, nil)
	}
	alice.post(ch, "a1", 201)
	owner.post(ch, "o1", 201)
	del := func(u *user, id string, code int) {
		u.chanCall("POST", "/v1/channels/"+ch+"/mod", map[string]any{"action": "delete", "post_id": id}, code, nil)
	}
	del(bob, "pa1-000000", 403)   // fremder Beitrag, kein Moderator
	del(alice, "po1-000000", 403) // Beitrag des Besitzers
	if got := countPosts(bob.entries(ch)); got != 2 {
		t.Fatalf("Beiträge nach abgelehnten Löschungen = %d", got)
	}
	del(alice, "pa1-000000", 200) // eigener Beitrag
	if got := countPosts(bob.entries(ch)); got != 1 {
		t.Fatalf("Beiträge nach eigener Löschung = %d", got)
	}
	del(alice, "pa1-000000", 404) // schon gelöscht
}
