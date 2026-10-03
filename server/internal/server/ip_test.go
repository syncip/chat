package server

import "testing"

func TestIsIPHostAndScheme(t *testing.T) {
	for d, want := range map[string]bool{
		"192.168.1.10:8080": true, "10.0.0.1": true, "127.0.0.1:9": true,
		"chat.example.org": false, "localhost:8080": false, "chat.example.org:8443": false,
	} {
		if got := isIPHost(d); got != want {
			t.Errorf("isIPHost(%q)=%v want %v", d, got, want)
		}
	}
}
