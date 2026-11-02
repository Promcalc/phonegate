// PhoneGate backend B0/B1: inbound-only phone authorization broker.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"phonegate/backend/internal/config"
	"phonegate/backend/internal/core"
	"phonegate/backend/internal/httpapi"
	"phonegate/backend/internal/links"
	"phonegate/backend/internal/model"
	"phonegate/backend/internal/store"
)

const version = "0.1.0-b1"

func main() {
	cfgPath := flag.String("config", "config.yaml", "path to YAML configuration")
	check := flag.Bool("check", false, "validate configuration and exit")
	showVersion := flag.Bool("version", false, "print version and exit")
	flag.Parse()

	if *showVersion {
		fmt.Println("phonegate-server", version)
		return
	}

	cfg, err := config.Load(*cfgPath)
	if err != nil {
		fmt.Fprintln(os.Stderr, "config:", err)
		os.Exit(2)
	}
	logger := newLogger(cfg)
	slog.SetDefault(logger)
	if *check {
		fmt.Println("OK", *cfgPath)
		return
	}

	st, err := store.Open(cfg.Storage.Driver)
	if err != nil {
		logger.Error("storage.open_failed", "error", err)
		os.Exit(2)
	}
	defer st.Close()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	if err := preload(ctx, cfg, st, logger); err != nil {
		logger.Error("startup.preload_failed", "error", err)
		os.Exit(2)
	}

	lm := links.NewManager(cfg.Server.PublicURL, cfg.Verification.Links.PagePath, cfg.Verification.Links.Secret)
	svc := core.NewService(cfg, st, logger, lm)
	go janitor(ctx, svc, logger)

	api := httpapi.New(cfg, svc, logger)
	httpServer := &http.Server{
		Addr:              cfg.Server.Listen,
		Handler:           api.Handler(),
		ReadTimeout:       cfg.Server.ReadTimeout.Std(),
		ReadHeaderTimeout: 5 * time.Second,
		WriteTimeout:      cfg.Server.WriteTimeout.Std(),
		IdleTimeout:       cfg.Server.IdleTimeout.Std(),
		ErrorLog:          slog.NewLogLogger(logger.Handler(), slog.LevelError),
	}

	sigCtx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() {
		<-sigCtx.Done()
		logger.Info("server.shutdown")
		shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer shutdownCancel()
		_ = httpServer.Shutdown(shutdownCtx)
	}()

	exchangeURL := strings.TrimRight(cfg.Server.PublicURL, "/") + cfg.Gateway.ExchangePath
	demoURL := strings.TrimRight(cfg.Server.PublicURL, "/") + "/demo"

	// Android app config: header name and value for /exchange
	authHeader := "Authorization"
	if len(cfg.Gateway.AuthHeaders) > 0 {
		authHeader = cfg.Gateway.AuthHeaders[0]
	}
	// The plaintext token for the demo gateway (from config.example.yaml)
	gatewayToken := "test-gateway-token"
	authValue := "Bearer " + gatewayToken

	logger.Info("server.started",
		"version", version,
		"listen", cfg.Server.Listen,
		"storage", cfg.Storage.Driver,
		"tenants", len(cfg.Tenants),
		"gateways", len(cfg.Gateways),
		"links", cfg.Verification.Links.Enabled,
		"exchange_url", exchangeURL,
		"demo_url", demoURL,
		"android_auth_header", authHeader,
		"android_auth_value", authValue,
	)
	if cfg.Verification.Links.Enabled && len(cfg.Verification.Links.Secret) < 32 {
		logger.Warn("links.weak_secret")
	}
	if cfg.Storage.Driver == "memory" {
		logger.Warn("storage.memory", "message", "pending verifications and history will be lost on restart")
	}

	if cfg.Server.TLS.Enabled {
		err = httpServer.ListenAndServeTLS(cfg.Server.TLS.CertFile, cfg.Server.TLS.KeyFile)
	} else {
		err = httpServer.ListenAndServe()
	}
	if err != nil && !errors.Is(err, http.ErrServerClosed) {
		logger.Error("server.failed", "error", err)
		os.Exit(1)
	}
}

func newLogger(cfg *config.Config) *slog.Logger {
	level := slog.LevelInfo
	switch cfg.Logging.Level {
	case "debug":
		level = slog.LevelDebug
	case "warn":
		level = slog.LevelWarn
	case "error":
		level = slog.LevelError
	}
	opts := &slog.HandlerOptions{Level: level}
	if cfg.Logging.Format == "text" {
		return slog.New(slog.NewTextHandler(os.Stdout, opts))
	}
	return slog.New(slog.NewJSONHandler(os.Stdout, opts))
}

func preload(ctx context.Context, cfg *config.Config, st store.Store, logger *slog.Logger) error {
	now := time.Now().UTC()
	// Map pool -> gateway ID for number assignment
	poolToGateway := make(map[string]string)
	for _, item := range cfg.Gateways {
		status := model.GatewayPending
		if item.Enabled {
			status = model.GatewayActive
		} else {
			status = model.GatewayDisabled
		}
		gw := model.Gateway{
			ID:         item.ID,
			TokenHash:  item.TokenSHA256,
			Name:       item.Name,
			Pool:       item.Pool,
			Status:     status,
			CreatedAt:  now,
			LastSeenAt: now, // consider gateway online for demo
		}
		if err := st.UpsertGateway(ctx, gw); err != nil {
			return err
		}
		if item.Pool != "" {
			poolToGateway[item.Pool] = item.ID
		}
	}
	var numbers []model.GatewayNumber
	for pool, p := range cfg.Pools {
		gatewayID := poolToGateway[pool]
		for _, number := range p.Numbers {
			numbers = append(numbers, model.GatewayNumber{
				E164:           number,
				GatewayID:      gatewayID,
				Pool:           pool,
				Status:         model.NumberActive,
				FirstSeenAt:    now,
				LastSeenAt:     now,
				LastAssignedAt: now,
			})
		}
	}
	if err := st.PreloadNumbers(ctx, numbers); err != nil {
		return err
	}
	logger.Info("startup.preloaded", "gateways", len(cfg.Gateways), "numbers", len(numbers))
	return nil
}

func janitor(ctx context.Context, svc *core.Service, logger *slog.Logger) {
	ticker := time.NewTicker(10 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			svc.Expire(ctx)
		}
	}
}
