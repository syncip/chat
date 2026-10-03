// Package config liest die Server-Konfiguration aus Umgebungsvariablen.
package config

import (
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	Domain   string // öffentlicher Name dieses Servers (z. B. chat.example.org)
	Listen   string
	DataDir  string
	WebDir   string // Verzeichnis mit dem gebauten Web-Client (optional)
	AdminKey string // Token für /v1/admin/*

	// Registrierung: "invite" (Default), "open", "closed"
	Registration     string
	UserInvites      bool // dürfen Nutzer Einladungen erzeugen?
	RegistrationPoW  int  // führende Null-Bits für offene Registrierung (0 = aus)
	MaxFileSize      int64
	MaxAttachments   int   // pro Nachricht (clientseitig durchgesetzt, in server-info veröffentlicht)
	MaxMessageTotal  int64 // pro Nachricht
	MaxMessageText   int
	MaxEnvelopeSize  int64 // maximale Größe eines Postfach-Blobs
	UserQuota        int64
	BlobRetention    time.Duration
	MessageRetention time.Duration

	// Föderation: "open" (Default), "allowlist", "closed"
	Federation      string
	FedAllow        []string
	FedBlock        []string
	FedInsecure     bool // http statt https (nur Tests/lokal)
	TrustProxyHdr   string
	CSPConnectExtra string // zusätzliche connect-src-Quellen (z. B. http://localhost:* für lokale Tests)
	RatePerMinute   int
	MaxMailboxes    int
	MaxKeyPackages  int
}

func Load() (*Config, error) {
	c := &Config{
		Domain:           env("CHAT_DOMAIN", "localhost:8080"),
		Listen:           env("CHAT_LISTEN", ":8080"),
		DataDir:          env("CHAT_DATA_DIR", "./data"),
		WebDir:           env("CHAT_WEB_DIR", ""),
		AdminKey:         env("CHAT_ADMIN_KEY", ""),
		Registration:     env("CHAT_REGISTRATION", "invite"),
		UserInvites:      envBool("CHAT_USER_INVITES", false),
		RegistrationPoW:  int(envInt("CHAT_REGISTRATION_POW", 20)),
		MaxFileSize:      envInt("CHAT_MAX_FILE_SIZE", 100<<20),
		MaxAttachments:   int(envInt("CHAT_MAX_MESSAGE_ATTACHMENTS", 10)),
		MaxMessageTotal:  envInt("CHAT_MAX_MESSAGE_TOTAL_SIZE", 500<<20),
		MaxMessageText:   int(envInt("CHAT_MAX_MESSAGE_TEXT", 64<<10)),
		MaxEnvelopeSize:  envInt("CHAT_MAX_ENVELOPE_SIZE", 256<<10),
		UserQuota:        envInt("CHAT_USER_QUOTA", 10<<30),
		BlobRetention:    envDays("CHAT_BLOB_RETENTION_DAYS", 30),
		MessageRetention: envDays("CHAT_MESSAGE_RETENTION_DAYS", 30),
		Federation:       env("CHAT_FEDERATION", "open"),
		FedAllow:         envList("CHAT_FEDERATION_ALLOW"),
		FedBlock:         envList("CHAT_FEDERATION_BLOCK"),
		FedInsecure:      envBool("CHAT_FEDERATION_INSECURE_HTTP", false) || envBool("CHAT_FEDERATION_ALLOW_PRIVATE", false),
		TrustProxyHdr:    env("CHAT_TRUST_PROXY_HEADER", ""),
		CSPConnectExtra:  env("CHAT_CSP_CONNECT_EXTRA", ""),
		RatePerMinute:    int(envInt("CHAT_RATE_PER_MINUTE", 600)),
		MaxMailboxes:     int(envInt("CHAT_MAX_MAILBOXES", 5000)),
		MaxKeyPackages:   int(envInt("CHAT_MAX_KEYPACKAGES", 200)),
	}
	switch c.Registration {
	case "invite", "open", "closed":
	default:
		return nil, fmt.Errorf("CHAT_REGISTRATION: invite|open|closed")
	}
	switch c.Federation {
	case "open", "allowlist", "closed":
	default:
		return nil, fmt.Errorf("CHAT_FEDERATION: open|allowlist|closed")
	}
	return c, nil
}

func env(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

func envInt(k string, d int64) int64 {
	if v := os.Getenv(k); v != "" {
		if n, err := strconv.ParseInt(v, 10, 64); err == nil {
			return n
		}
	}
	return d
}

func envBool(k string, d bool) bool {
	if v := os.Getenv(k); v != "" {
		b, err := strconv.ParseBool(v)
		if err == nil {
			return b
		}
	}
	return d
}

func envDays(k string, d int64) time.Duration { return time.Duration(envInt(k, d)) * 24 * time.Hour }

func envList(k string) []string {
	var out []string
	for _, s := range strings.Split(os.Getenv(k), ",") {
		if s = strings.ToLower(strings.TrimSpace(s)); s != "" {
			out = append(out, s)
		}
	}
	return out
}
