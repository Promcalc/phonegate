package core

import (
	"context"
	"errors"
	"log/slog"
	"strings"
	"testing"
	"time"

	"phonegate/backend/internal/config"
	"phonegate/backend/internal/links"
	"phonegate/backend/internal/model"
	"phonegate/backend/internal/store"
)

const (
	testGatewayNumber = "+79005550001"
	testPhone         = "+79001234567"
	testGatewayID     = "gw_test"
	testTenant        = "tenant_test"
)

func testConfig() *config.Config {
	cfg := config.Defaults()
	cfg.Server.PublicURL = "https://auth.example.test"
	cfg.Storage.Driver = "memory"
	cfg.Verification.DefaultTTL = config.Duration(5 * time.Minute)
	cfg.Verification.MaxTTL = config.Duration(15 * time.Minute)
	cfg.Verification.RetryInterval = config.Duration(10 * time.Second)
	cfg.Verification.Links.Enabled = true
	cfg.Verification.Links.Secret = "0123456789abcdef0123456789abcdef"
	cfg.Verification.Links.PagePath = "/v"
	cfg.Gateway.RequireApproval = false
	cfg.Pools = map[string]config.PoolConfig{
		"pool_test": {Numbers: []string{testGatewayNumber}},
	}
	cfg.Tenants = map[string]config.TenantConfig{
		testTenant: {
			Name:    "Test",
			Enabled: true,
			Pools:   []string{"pool_test"},
			Verification: config.TenantVerification{
				Methods:       []string{model.MethodSMS, model.MethodCall, model.MethodAny},
				DefaultMethod: model.MethodSMS,
				TTL:           config.Duration(5 * time.Minute),
				CodeLength:    6,
				MaxAttempts:   3,
			},
		},
	}
	cfg.Gateways = []config.GatewayItem{{
		ID: testGatewayID, Name: "Test gateway", Pool: "pool_test", Enabled: true,
		TokenSHA256: strings.Repeat("a", 64),
	}}
	return &cfg
}

func testService(t *testing.T) (*Service, *store.Memory, *time.Time, *model.Gateway) {
	t.Helper()
	cfg := testConfig()
	st := store.NewMemory()
	now := time.Date(2026, 10, 21, 12, 0, 0, 0, time.UTC)
	if err := st.UpsertGateway(context.Background(), model.Gateway{
		ID: testGatewayID, TokenHash: strings.Repeat("a", 64), Name: "Test gateway",
		Pool: "pool_test", Status: model.GatewayActive, LastSeenAt: now, CreatedAt: now,
	}); err != nil {
		t.Fatal(err)
	}
	if err := st.PreloadNumbers(context.Background(), []model.GatewayNumber{{
		E164: testGatewayNumber, Pool: "pool_test", Status: model.NumberActive, FirstSeenAt: now,
	}}); err != nil {
		t.Fatal(err)
	}
	logger := slog.New(slog.NewTextHandler(testWriter{t}, &slog.HandlerOptions{Level: slog.LevelError}))
	lm := links.NewManager(cfg.Server.PublicURL, "/v", cfg.Verification.Links.Secret)
	lm.SetClock(func() time.Time { return now })
	svc := NewService(cfg, st, logger, lm)
	svc.SetClock(func() time.Time { return now })
	gateway, err := st.GatewayByID(context.Background(), testGatewayID)
	if err != nil {
		t.Fatal(err)
	}
	// Register the gateway number, as the first real exchange would.
	if err := svc.HandleExchange(context.Background(), gateway, &ExchangeRequest{
		SyncID: "init", Sims: []SimReport{{Phone: testGatewayNumber}},
	}); err != nil {
		t.Fatal(err)
	}
	return svc, st, &now, gateway
}

type testWriter struct{ t *testing.T }

func (w testWriter) Write(p []byte) (int, error) {
	w.t.Log(strings.TrimSpace(string(p)))
	return len(p), nil
}

func exchangeSMS(t *testing.T, svc *Service, gateway *model.Gateway, now time.Time, peer, text string) {
	t.Helper()
	err := svc.HandleExchange(context.Background(), gateway, &ExchangeRequest{
		SyncID: "sync-" + text,
		Sims: []SimReport{{
			Phone: testGatewayNumber,
			SMS: []SMSItem{{
				Time: now.Format(time.RFC3339), Text: text, Phone: peer,
			}},
		}},
	})
	if err != nil {
		t.Fatal(err)
	}
}

func exchangeCall(t *testing.T, svc *Service, gateway *model.Gateway, now time.Time, peer, direction string) {
	t.Helper()
	err := svc.HandleExchange(context.Background(), gateway, &ExchangeRequest{
		SyncID: "sync-call",
		Sims: []SimReport{{
			Phone: testGatewayNumber,
			Call: []CallItem{{
				Time: now.Format(time.RFC3339), Phone: peer, Duration: 0, Direction: direction,
			}},
		}},
	})
	if err != nil {
		t.Fatal(err)
	}
}

func TestSMSVerification(t *testing.T) {
	svc, _, now, gateway := testService(t)
	created, err := svc.Create(context.Background(), testTenant, CreateRequest{Phone: testPhone, Method: "sms"}, "127.0.0.1", "req1", "", false)
	if err != nil {
		t.Fatal(err)
	}
	if created.Code == "" || created.Links == nil || created.Links.SMSURI == nil {
		t.Fatalf("bad created response: %#v", created)
	}
	exchangeSMS(t, svc, gateway, *now, testPhone, created.Code)
	v, err := svc.Get(context.Background(), testTenant, created.Verification.ID)
	if err != nil {
		t.Fatal(err)
	}
	if v.Status != model.StatusVerified || v.VerifiedAt == nil {
		t.Fatalf("status = %s", v.Status)
	}
	if v.Phone == nil || *v.Phone != testPhone {
		t.Fatalf("phone = %#v", v.Phone)
	}
}

func TestMultipleSMSCodesOnOneGateway(t *testing.T) {
	svc, _, now, gateway := testService(t)
	first, err := svc.Create(context.Background(), testTenant, CreateRequest{Phone: testPhone, Method: "sms"}, "127.0.0.1", "r1", "", false)
	if err != nil {
		t.Fatal(err)
	}
	second, err := svc.Create(context.Background(), testTenant, CreateRequest{Phone: "+79007654321", Method: "sms"}, "127.0.0.1", "r2", "", false)
	if err != nil {
		t.Fatal(err)
	}
	if first.Code == second.Code {
		t.Fatal("codes must be unique on one gateway")
	}
	exchangeSMS(t, svc, gateway, *now, testPhone, first.Code)
	exchangeSMS(t, svc, gateway, *now, "+79007654321", second.Code)
	a, _ := svc.Get(context.Background(), testTenant, first.Verification.ID)
	b, _ := svc.Get(context.Background(), testTenant, second.Verification.ID)
	if a.Status != model.StatusVerified || b.Status != model.StatusVerified {
		t.Fatalf("statuses: %s, %s", a.Status, b.Status)
	}
}

func TestSamePhoneMultipleSMSCodes(t *testing.T) {
	svc, _, now, gateway := testService(t)
	first, err := svc.Create(context.Background(), testTenant, CreateRequest{Phone: testPhone, Method: "sms"}, "127.0.0.1", "r1", "", false)
	if err != nil {
		t.Fatal(err)
	}
	second, err := svc.Create(context.Background(), testTenant, CreateRequest{Phone: testPhone, Method: "sms"}, "127.0.0.1", "r2", "", false)
	if err != nil {
		t.Fatal(err)
	}
	exchangeSMS(t, svc, gateway, *now, testPhone, second.Code)
	exchangeSMS(t, svc, gateway, *now, testPhone, first.Code)
	a, _ := svc.Get(context.Background(), testTenant, first.Verification.ID)
	b, _ := svc.Get(context.Background(), testTenant, second.Verification.ID)
	if a.Status != model.StatusVerified || b.Status != model.StatusVerified {
		t.Fatalf("statuses: %s, %s", a.Status, b.Status)
	}
}

func TestCodeFirst(t *testing.T) {
	svc, _, now, gateway := testService(t)
	created, err := svc.Create(context.Background(), testTenant, CreateRequest{Mode: model.ModeCodeFirst, Method: "sms"}, "127.0.0.1", "r1", "", false)
	if err != nil {
		t.Fatal(err)
	}
	if created.Verification.Phone != nil {
		t.Fatal("phone must be nil before SMS")
	}
	exchangeSMS(t, svc, gateway, *now, testPhone, created.Code)
	v, _ := svc.Get(context.Background(), testTenant, created.Verification.ID)
	if v.Status != model.StatusVerified || v.Phone == nil || *v.Phone != testPhone {
		t.Fatalf("code-first result: %#v", v)
	}
}

func TestCallVerificationAndConflict(t *testing.T) {
	svc, _, now, gateway := testService(t)
	created, err := svc.Create(context.Background(), testTenant, CreateRequest{Phone: testPhone, Method: "call"}, "127.0.0.1", "r1", "", false)
	if err != nil {
		t.Fatal(err)
	}
	if created.Verification.CodeHash != nil {
		t.Fatal("call session must not have a code")
	}
	_, err = svc.Create(context.Background(), testTenant, CreateRequest{Phone: testPhone, Method: "call"}, "127.0.0.1", "r2", "", false)
	var apiErr *Error
	if !errors.As(err, &apiErr) || apiErr.Code != "verification_already_pending" {
		t.Fatalf("expected call conflict, got %v", err)
	}
	exchangeCall(t, svc, gateway, *now, testPhone, model.DirectionMissed)
	v, _ := svc.Get(context.Background(), testTenant, created.Verification.ID)
	if v.Status != model.StatusVerified {
		t.Fatalf("call status = %s", v.Status)
	}
}

func TestIdempotency(t *testing.T) {
	svc, _, _, _ := testService(t)
	req := CreateRequest{Phone: testPhone, Method: "sms"}
	first, err := svc.Create(context.Background(), testTenant, req, "127.0.0.1", "r1", "idem-1", false)
	if err != nil {
		t.Fatal(err)
	}
	second, err := svc.Create(context.Background(), testTenant, req, "127.0.0.1", "r2", "idem-1", false)
	if err != nil {
		t.Fatal(err)
	}
	if first.Verification.ID != second.Verification.ID {
		t.Fatal("idempotent request created a new session")
	}
	req.Method = "call"
	_, err = svc.Create(context.Background(), testTenant, req, "127.0.0.1", "r3", "idem-1", false)
	var apiErr *Error
	if !errors.As(err, &apiErr) || apiErr.Code != "idempotency_conflict" {
		t.Fatalf("expected idempotency conflict, got %v", err)
	}
}

func TestExpiryAndWrongCode(t *testing.T) {
	svc, _, now, gateway := testService(t)
	created, err := svc.Create(context.Background(), testTenant, CreateRequest{Phone: testPhone, Method: "sms", TTLSeconds: 60}, "127.0.0.1", "r1", "", false)
	if err != nil {
		t.Fatal(err)
	}
	wrong := "000000"
	if wrong == created.Code {
		wrong = "000001"
	}
	exchangeSMS(t, svc, gateway, *now, testPhone, wrong)
	v, _ := svc.Get(context.Background(), testTenant, created.Verification.ID)
	if v.Status != model.StatusPending {
		t.Fatalf("wrong code changed status: %s", v.Status)
	}
	*now = now.Add(2 * time.Minute)
	svc.Expire(context.Background())
	v, _ = svc.Get(context.Background(), testTenant, created.Verification.ID)
	if v.Status != model.StatusExpired {
		t.Fatalf("expiry status: %s", v.Status)
	}
}

func TestDedup(t *testing.T) {
	svc, _, now, gateway := testService(t)
	created, err := svc.Create(context.Background(), testTenant, CreateRequest{Phone: testPhone, Method: "sms"}, "127.0.0.1", "r1", "", false)
	if err != nil {
		t.Fatal(err)
	}
	exchangeSMS(t, svc, gateway, *now, testPhone, created.Code)
	exchangeSMS(t, svc, gateway, *now, testPhone, created.Code)
	v, _ := svc.Get(context.Background(), testTenant, created.Verification.ID)
	if v.Status != model.StatusVerified {
		t.Fatalf("dedup status: %s", v.Status)
	}
}

func TestLinksToken(t *testing.T) {
	svc, _, _, _ := testService(t)
	created, err := svc.Create(context.Background(), testTenant, CreateRequest{Phone: testPhone, Method: "sms"}, "127.0.0.1", "r1", "", false)
	if err != nil {
		t.Fatal(err)
	}
	if created.Links == nil {
		t.Fatal("links missing")
	}
	token := created.Links.PageURL[strings.LastIndex(created.Links.PageURL, "/")+1:]
	payload, err := svc.Links().Parse(token)
	if err != nil {
		t.Fatal(err)
	}
	if payload.Session != created.Verification.ID || payload.Code != created.Code {
		t.Fatalf("payload: %#v", payload)
	}
}

func TestInvalidPeerDroppedByDefault(t *testing.T) {
	svc, st, now, gateway := testService(t)
	exchangeSMS(t, svc, gateway, *now, "Beeline", "code 123456")
	exchangeCall(t, svc, gateway, *now, "Beeline", model.DirectionMissed)
	if events := st.Events(); len(events) != 0 {
		t.Fatalf("invalid events must be dropped, got %d", len(events))
	}
}

func TestInvalidPeerAcceptedWhenValidationDisabled(t *testing.T) {
	svc, st, now, gateway := testService(t)
	svc.cfg.Gateway.ValidatePeerNumbers = false
	exchangeSMS(t, svc, gateway, *now, "Beeline", "code 123456")
	exchangeCall(t, svc, gateway, *now, "Beeline", model.DirectionMissed)

	events := st.Events()
	if len(events) != 2 {
		t.Fatalf("events: %d, want 2", len(events))
	}
	for _, ev := range events {
		if ev.PeerPhone != "Beeline" {
			t.Fatalf("peer = %q, want raw value", ev.PeerPhone)
		}
	}
}
