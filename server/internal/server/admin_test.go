package server_test

import (
	"bufio"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/syncip/chat/server/internal/server"
)

func TestFirstUserIsAdminStatsAndSettings(t *testing.T) {
	n := newNode(t, nil)
	admin := n.mustUser("martin")
	other := n.mustUser("anna")
	var me struct {
		Name  string
		Admin bool
	}
	admin.call("GET", "/v1/me", nil, 200, &me)
	if !me.Admin {
		t.Fatal("erster Nutzer muss Admin sein")
	}
	other.call("GET", "/v1/me", nil, 200, &me)
	if me.Admin {
		t.Fatal("zweiter Nutzer darf kein Admin sein")
	}
	other.call("GET", "/v1/admin/stats", nil, 403, nil)
	other.call("PUT", "/v1/admin/settings", map[string]any{}, 403, nil)

	var st struct {
		Stats map[string]any `json:"stats"`
	}
	admin.call("GET", "/v1/admin/stats", nil, 200, &st)
	if st.Stats["users"].(float64) != 2 || st.Stats["admins"].(float64) != 1 {
		t.Fatalf("stats %+v", st.Stats)
	}
	var us struct{ Users []map[string]any }
	admin.call("GET", "/v1/admin/users", nil, 200, &us)
	if len(us.Users) != 2 {
		t.Fatalf("users %+v", us)
	}

	// Einstellungen ändern wirkt sofort
	var cur map[string]any
	admin.call("GET", "/v1/admin/settings", nil, 200, &cur)
	for k, v := range cur { // Testkonfiguration hat Stunden-Aufbewahrung (0 Tage): auf gültige Werte setzen
		if f, ok := v.(float64); ok && f < 1 && k != "registration_pow" {
			cur[k] = 1
		}
	}
	cur["registration"] = "closed"
	cur["max_file_size"] = 1234
	admin.call("PUT", "/v1/admin/settings", cur, 200, nil)
	req, _ := http.NewRequest("GET", n.ts.URL+"/v1/server-info", nil)
	var info struct {
		Registration string
		Limits       struct {
			MaxFileSize int64 `json:"max_file_size"`
		}
	}
	mustJSON(t, do(t, req), 200, &info)
	if info.Registration != "closed" || info.Limits.MaxFileSize != 1234 {
		t.Fatalf("info %+v", info)
	}
	cur["registration"] = "bogus"
	admin.call("PUT", "/v1/admin/settings", cur, 400, nil)
	// weitere Admins ernennen; der letzte Admin bleibt
	admin.call("PUT", "/v1/admin/users/anna/admin", map[string]any{"admin": true}, 200, nil)
	other.call("GET", "/v1/admin/stats", nil, 200, nil)
	admin.call("PUT", "/v1/admin/users/martin/admin", map[string]any{"admin": false}, 200, nil)
	other.call("PUT", "/v1/admin/users/anna/admin", map[string]any{"admin": false}, 409, nil)
}

func TestAccountSyncCAS(t *testing.T) {
	n := newNode(t, nil)
	u := n.mustUser("martin")
	var g struct {
		Version int64
		Data    string
	}
	u.call("GET", "/v1/sync", nil, 200, &g)
	if g.Version != 0 {
		t.Fatal("neu = Version 0")
	}
	u.call("PUT", "/v1/sync", map[string]any{"base_version": 0, "data": b64.EncodeToString([]byte("eins"))}, 200, nil)
	u.call("PUT", "/v1/sync", map[string]any{"base_version": 0, "data": b64.EncodeToString([]byte("zwei"))}, 409, &g)
	if g.Version != 1 || g.Data != b64.EncodeToString([]byte("eins")) {
		t.Fatalf("conflict %+v", g)
	}
	u.call("PUT", "/v1/sync", map[string]any{"base_version": 1, "data": b64.EncodeToString([]byte("zwei"))}, 200, nil)
	u.call("PUT", "/v1/sync", map[string]any{"base_version": 2, "data": ""}, 400, nil)
}

func TestPublicChannelAndNtfyWebhooks(t *testing.T) {
	n := newNode(t, nil)
	owner := n.mustUser("owner")
	pub := newChannel(owner, map[string]any{"join_mode": "open", "public": true})
	// Titel im Klartext
	var info struct {
		Title  string
		Policy map[string]any
	}
	mustJSON(t, do(t, mustReq("GET", n.ts.URL+"/v1/channels/"+pub)), 200, &info)
	_ = info
	priv := newChannel(owner, map[string]any{"join_mode": "open"})
	// ohne Anmeldung: privater Kanal nicht lesbar, öffentlicher schon
	mustJSON(t, do(t, mustReq("GET", n.ts.URL+"/v1/channels/"+priv+"/public/log")), 404, nil)
	var log struct {
		Public  bool
		Entries []map[string]any
	}
	mustJSON(t, do(t, mustReq("GET", n.ts.URL+"/v1/channels/"+pub+"/public/log")), 200, &log)
	if !log.Public || len(log.Entries) == 0 {
		t.Fatalf("public log %+v", log)
	}

	// Webhook (öffentlicher Kanal braucht keinen Schlüssel)
	var hk struct{ ID, Token, Path string }
	owner.chanCall("POST", "/v1/channels/"+pub+"/hooks", map[string]any{"name": "Monitoring"}, 201, &hk)
	post := func(method, path, body string, hdr map[string]string, want int) map[string]any {
		req, _ := http.NewRequest(method, n.ts.URL+path, strings.NewReader(body))
		for k, v := range hdr {
			req.Header.Set(k, v)
		}
		resp := do(t, req)
		var out map[string]any
		mustJSON(t, resp, want, &out)
		return out
	}
	post("POST", hk.Path, "Backup fertig", map[string]string{"Title": "Nachtlauf", "Priority": "high", "Tags": "white_check_mark,backup"}, 200)
	post("GET", hk.Path+"/publish?message=per+GET&title=T", "", nil, 200)
	post("POST", hk.Path, `{"message":"per JSON","title":"J","priority":5,"tags":["warning"]}`, map[string]string{"Content-Type": "application/json"}, 200)
	post("POST", "/h/falsch", "x", nil, 404)
	post("POST", hk.Path, "", nil, 400)
	mustJSON(t, do(t, mustReq("GET", n.ts.URL+"/v1/channels/"+pub+"/public/log")), 200, &log)
	var texts []string
	for _, e := range log.Entries {
		if e["hook"] != nil {
			raw, _ := b64.DecodeString(e["data"].(string))
			var p struct{ Parts []struct{ Body string } }
			if err := json.Unmarshal(raw, &p); err != nil {
				t.Fatal(err)
			}
			texts = append(texts, p.Parts[0].Body)
		}
	}
	if len(texts) != 3 || !strings.Contains(texts[0], "**Nachtlauf**") || !strings.Contains(texts[0], "✅") || !strings.Contains(texts[0], "#backup") || !strings.Contains(texts[2], "🚨") {
		t.Fatalf("texts %q", texts)
	}

	// SSE ohne Konto liefert die Einträge
	req := mustReq("GET", n.ts.URL+"/v1/channels/"+pub+"/public/events")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	sc := bufio.NewScanner(resp.Body)
	got := 0
	deadline := time.Now().Add(5 * time.Second)
	for sc.Scan() && time.Now().Before(deadline) {
		if strings.HasPrefix(sc.Text(), "data: ") {
			got++
		}
		if got >= 4 {
			break
		}
	}
	if got < 4 {
		t.Fatalf("SSE lieferte %d Einträge", got)
	}

	// Privater Kanal: Schlüssel nötig und wird geprüft; Beitrag ist verschlüsselt und mit dem Schlüssel lesbar
	key := make([]byte, 32)
	for i := range key {
		key[i] = byte(i + 1)
	}
	title, _ := server.SealEnvelope(key, 3, []byte("title"), []byte("Privat"))
	var pc struct{ ID string }
	owner.call("POST", "/v1/channels", map[string]any{"title_enc": b64.EncodeToString(title), "policy": map[string]any{"join_mode": "open"}}, 201, &pc)
	owner.chanCall("POST", "/v1/channels/"+pc.ID+"/hooks", map[string]any{"name": "x"}, 400, nil)
	wrong := make([]byte, 32)
	owner.chanCall("POST", "/v1/channels/"+pc.ID+"/hooks", map[string]any{"name": "x", "key": b64.EncodeToString(wrong)}, 400, nil)
	owner.chanCall("POST", "/v1/channels/"+pc.ID+"/hooks", map[string]any{"name": "x", "key": b64.EncodeToString(key)}, 201, &hk)
	post("POST", hk.Path, "geheim", nil, 200)
	var es struct{ Entries []map[string]any }
	owner.chanCall("GET", "/v1/channels/"+pc.ID+"/log?after=0", nil, 200, &es)
	found := false
	for _, e := range es.Entries {
		if e["hook"] == "x" {
			blob, _ := b64.DecodeString(e["data"].(string))
			k, g, p, err := server.OpenEnvelope(key, blob)
			if err != nil || k != 3 || string(g) != pc.ID || !strings.Contains(string(p), "geheim") {
				t.Fatalf("hook post %v %v %s", err, k, p)
			}
			found = true
		}
	}
	if !found {
		t.Fatal("kein Webhook-Beitrag")
	}
	// Liste und Löschen
	var hl struct{ Hooks []map[string]any }
	owner.chanCall("GET", "/v1/channels/"+pc.ID+"/hooks", nil, 200, &hl)
	if len(hl.Hooks) != 1 {
		t.Fatalf("hooks %+v", hl)
	}
	owner.chanCall("DELETE", "/v1/channels/"+pc.ID+"/hooks/"+hl.Hooks[0]["id"].(string), nil, 200, nil)
	post("POST", hk.Path, "weg", nil, 404)
}

func TestAdminSearchBanAndRateLimit(t *testing.T) {
	n := newNode(t, nil)
	admin := n.mustUser("martin")
	anna := n.mustUser("anna")
	bob := n.mustUser("bob")

	var us struct{ Users []map[string]any }
	admin.call("GET", "/v1/admin/users?q=AN", nil, 200, &us)
	if len(us.Users) != 1 || us.Users[0]["name"] != "anna" {
		t.Fatalf("suche: %+v", us)
	}
	admin.call("GET", "/v1/admin/users?q=%25", nil, 200, &us) // Wildcard wird escaped
	if len(us.Users) != 0 {
		t.Fatalf("wildcard: %+v", us)
	}

	// Nicht-Admins dürfen nicht; Admin nicht sich selbst / keine Admins
	anna.call("PUT", "/v1/admin/users/bob/restrict", map[string]any{"ban": "perm"}, 403, nil)
	admin.call("PUT", "/v1/admin/users/martin/restrict", map[string]any{"ban": "perm"}, 409, nil)
	admin.call("PUT", "/v1/admin/users/nobody/restrict", map[string]any{"ban": "perm"}, 404, nil)
	admin.call("PUT", "/v1/admin/users/anna/restrict", map[string]any{"ban": "temp"}, 400, nil)

	// temporäre Sperre
	admin.call("PUT", "/v1/admin/users/anna/restrict", map[string]any{"ban": "temp", "ban_minutes": 30, "reason": "spam"}, 200, nil)
	anna.call("GET", "/v1/me", nil, 403, nil)
	admin.call("GET", "/v1/admin/users?q=anna", nil, 200, &us)
	if us.Users[0]["banned_until"].(float64) <= float64(time.Now().Unix()) || us.Users[0]["ban_reason"] != "spam" {
		t.Fatalf("%+v", us.Users[0])
	}
	// aufheben
	admin.call("PUT", "/v1/admin/users/anna/restrict", map[string]any{"ban": ""}, 200, nil)
	anna.call("GET", "/v1/me", nil, 200, nil)

	// dauerhaft
	admin.call("PUT", "/v1/admin/users/bob/restrict", map[string]any{"ban": "perm"}, 200, nil)
	bob.call("GET", "/v1/me", nil, 403, nil)
	admin.call("PUT", "/v1/admin/users/bob/restrict", map[string]any{"ban": ""}, 200, nil)

	// Anfragelimit: 2 pro Minute
	admin.call("PUT", "/v1/admin/users/bob/restrict", map[string]any{"rate_limit": 2, "rate_minutes": 10}, 200, nil)
	bob.call("GET", "/v1/me", nil, 200, nil)
	bob.call("GET", "/v1/me", nil, 200, nil)
	bob.call("GET", "/v1/me", nil, 429, nil)
	admin.call("PUT", "/v1/admin/users/bob/restrict", map[string]any{}, 200, nil)
	bob.call("GET", "/v1/me", nil, 200, nil)
}

func TestTransferOneShot(t *testing.T) {
	n := newNode(t, nil)
	u := n.mustUser("martin")
	var out struct {
		ID        string `json:"id"`
		ExpiresIn int    `json:"expires_in"`
	}
	u.call("POST", "/v1/transfer", map[string]any{"data": "AAEC"}, 201, &out)
	if len(out.ID) < 20 || out.ExpiresIn != 300 {
		t.Fatalf("%+v", out)
	}
	req, _ := http.NewRequest("GET", n.ts.URL+"/v1/transfer/"+out.ID, nil)
	var got struct{ Data string }
	mustJSON(t, do(t, req), 200, &got)
	if got.Data != "AAEC" {
		t.Fatalf("%+v", got)
	}
	mustJSON(t, do(t, req), 404, nil) // einmalig
	u.call("POST", "/v1/transfer", map[string]any{"data": "!!"}, 400, nil)
	for i := 0; i < 3; i++ {
		u.call("POST", "/v1/transfer", map[string]any{"data": "AAEC"}, 201, nil)
	}
	u.call("POST", "/v1/transfer", map[string]any{"data": "AAEC"}, 429, nil)
}

func TestChatCodes(t *testing.T) {
	n := newNode(t, nil)
	martin := n.mustUser("martin")
	anna := n.mustUser("anna")
	card := func(user string) string {
		j := `{"a":"` + user + `@` + n.domain + `","d":"` + n.domain + `","m":"mbmbmbmb","t":"tktktktk","k":"kkkkkkkk"}`
		return strings.TrimRight(base64.RawURLEncoding.EncodeToString([]byte(j)), "=")
	}
	martin.call("PUT", "/v1/code", map[string]any{"code": "x", "card": card("martin")}, 400, nil)
	martin.call("PUT", "/v1/code", map[string]any{"code": "martinistcool", "card": card("anna")}, 400, nil) // fremde Karte
	martin.call("PUT", "/v1/code", map[string]any{"code": "MartinIstCool", "card": card("martin")}, 200, nil)
	anna.call("PUT", "/v1/code", map[string]any{"code": "martinistcool", "card": card("anna")}, 409, nil)
	var got struct{ Card string }
	req, _ := http.NewRequest("GET", n.ts.URL+"/v1/codes/MARTINISTCOOL", nil)
	mustJSON(t, do(t, req), 200, &got)
	if got.Card != card("martin") {
		t.Fatalf("%+v", got)
	}
	var mine struct{ Code string }
	martin.call("GET", "/v1/code", nil, 200, &mine)
	if mine.Code != "martinistcool" {
		t.Fatalf("%+v", mine)
	}
	// Code wechseln: alter wird frei
	martin.call("PUT", "/v1/code", map[string]any{"code": "martin-neu", "card": card("martin")}, 200, nil)
	anna.call("PUT", "/v1/code", map[string]any{"code": "martinistcool", "card": card("anna")}, 200, nil)
	martin.call("DELETE", "/v1/code", nil, 200, nil)
	req, _ = http.NewRequest("GET", n.ts.URL+"/v1/codes/martin-neu", nil)
	mustJSON(t, do(t, req), 404, nil)
}

func TestChannelVisibilitySwitch(t *testing.T) {
	n := newNode(t, nil)
	owner, bob := n.mustUser("owner"), n.mustUser("bob")
	pub := newChannel(owner, map[string]any{"join_mode": "open", "public": true})
	var hk struct{ Path string }
	owner.chanCall("POST", "/v1/channels/"+pub+"/hooks", map[string]any{"name": "H"}, 201, &hk)
	setURL := "/v1/channels/" + pub + "/settings"
	key := make([]byte, 32)
	for i := range key {
		key[i] = byte(i + 1)
	}
	sealedTitle, err := server.SealEnvelope(key, 3, []byte("title"), []byte("Privat"))
	if err != nil {
		t.Fatal(err)
	}
	priv := map[string]any{"join_mode": "open", "public": false}
	// ohne neuen Titel/Schlüssel abgelehnt
	owner.chanCall("PUT", setURL, map[string]any{"policy": priv}, 400, nil)
	owner.chanCall("PUT", setURL, map[string]any{"policy": priv, "title_enc": b64.EncodeToString(sealedTitle), "hook_key": "AAAA"}, 400, nil)
	// nur der Besitzer
	bob.chanCall("PUT", setURL, map[string]any{"policy": priv}, 403, nil)
	owner.chanCall("PUT", setURL, map[string]any{"policy": priv, "title_enc": b64.EncodeToString(sealedTitle), "hook_key": b64.EncodeToString(key)}, 200, nil)
	mustJSON(t, do(t, mustReq("GET", n.ts.URL+"/v1/channels/"+pub+"/public/log")), 404, nil) // nicht mehr öffentlich lesbar
	// Webhook verschlüsselt nun mit dem neuen Schlüssel
	req, _ := http.NewRequest("POST", n.ts.URL+hk.Path, strings.NewReader("hallo"))
	mustJSON(t, do(t, req), 200, nil)
	// zurück auf öffentlich (Klartexttitel)
	owner.chanCall("PUT", setURL, map[string]any{"policy": map[string]any{"join_mode": "open", "public": true}, "title_enc": b64.EncodeToString([]byte("Öffentlich"))}, 200, nil)
	var log struct{ Public bool }
	mustJSON(t, do(t, mustReq("GET", n.ts.URL+"/v1/channels/"+pub+"/public/log")), 200, &log)
	if !log.Public {
		t.Fatal("wieder öffentlich erwartet")
	}
}
