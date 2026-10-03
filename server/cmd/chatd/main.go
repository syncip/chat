// chatd ist der Chat-Server.
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/syncip/chat/server/internal/config"
	"github.com/syncip/chat/server/internal/server"
	"github.com/syncip/chat/server/internal/store"
)

func main() {
	if len(os.Args) > 1 && os.Args[1] == "healthcheck" {
		os.Exit(healthcheck())
	}
	log := slog.New(slog.NewTextHandler(os.Stderr, nil))
	cfg, err := config.Load()
	if err != nil {
		log.Error("config", "err", err)
		os.Exit(2)
	}
	if err := os.MkdirAll(cfg.DataDir, 0o700); err != nil {
		log.Error("data dir", "err", err)
		os.Exit(1)
	}
	st, err := store.Open(cfg.DataDir)
	if err != nil {
		log.Error("store", "err", err)
		os.Exit(1)
	}
	defer st.Close()
	srv, err := server.New(cfg, st, log)
	if err != nil {
		log.Error("server", "err", err)
		os.Exit(1)
	}
	if tok, err := srv.BootstrapInvite(); err == nil && tok != "" {
		log.Info("erste Einladung (24 h gültig, einmalig)", "invite", tok)
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go srv.RunJanitor(ctx)

	hs := &http.Server{
		Addr: cfg.Listen, Handler: srv.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       120 * time.Second,
	}
	go func() {
		<-ctx.Done()
		sctx, c := context.WithTimeout(context.Background(), 10*time.Second)
		defer c()
		srv.Close()
		_ = hs.Shutdown(sctx)
	}()
	log.Info("chatd gestartet", "listen", cfg.Listen, "domain", cfg.Domain, "registration", cfg.Registration, "federation", cfg.Federation)
	if err := hs.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Error("listen", "err", err)
		os.Exit(1)
	}
}

// healthcheck prüft den lokalen Server (für Docker HEALTHCHECK, ohne curl im Image).
func healthcheck() int {
	addr := os.Getenv("CHAT_LISTEN")
	if addr == "" {
		addr = ":8080"
	}
	if strings.HasPrefix(addr, ":") {
		addr = "127.0.0.1" + addr
	}
	c := http.Client{Timeout: 3 * time.Second}
	r, err := c.Get("http://" + addr + "/v1/server-info")
	if err != nil || r.StatusCode != 200 {
		return 1
	}
	return 0
}
