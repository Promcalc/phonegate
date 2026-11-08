package httpapi

import (
	"bytes"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"phonegate/backend/internal/config"
	"phonegate/backend/internal/core"
	"phonegate/backend/internal/links"
	"phonegate/backend/internal/model"
	"phonegate/backend/internal/ratelimit"
	"phonegate/backend/internal/store"
)

const (
	apiKey       = "test-tenant-key"
	gatewayToken = "test-gateway-token"
	gatewayNum   = "+79005550001"
	userPhone    = "+79001234567"
	tenantID     = "tenant_test"
)

func newTestServer(t *testing.T) (*httptest.Server, *core.Service, *store.Memory) {
	t.Helper()
	ts := httptest.NewUnstartedServer(nil)
	cfg := config.Defaults()
	cfg.Server.PublicURL = "http://" + ts.Listener.Addr().String()
	cfg.Storage.Driver = "memory"
	cfg.Logging.AccessLog = false
	cfg.Verification.Links.Enabled = true
	cfg.Verification.Links.Secret = "0123456789abcdef0123456789abcdef"
	cfg.Verification.Links.PagePath = "/v"
	cfg.Verification.DefaultTTL = config.Duration(5 * time.Minute)
	cfg.Verification.MaxTTL = config.Duration(15 * time.Minute)
	cfg.Gateway.RequireApproval = false
	cfg.Pools = map[string]config.PoolConfig{"pool": {Numbers: []string{gatewayNum}}}
	cfg.Tenants = map[string]config.TenantConfig{
		tenantID: {
			Name:    "Test",
			Enabled: true,
			Pools:   []string{"pool"},
			Auth: config.TenantAuth{
				APIKeySHA256: config.HashSecret(apiKey),
			},
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
		ID: "gw_test", Name: "Test", TokenSHA256: config.HashSecret(gatewayToken), Pool: "pool", Enabled: true,
	}}

	st := store.NewMemory()
	now := time.Now().UTC()
	if err := st.UpsertGateway(nil, model.Gateway{
		ID: "gw_test", TokenHash: config.HashSecret(gatewayToken), Name: "Test",
		Pool: "pool", Status: model.GatewayActive, LastSeenAt: now, CreatedAt: now,
	}); err != nil {
		t.Fatal(err)
	}
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	lm := links.NewManager(cfg.Server.PublicURL, "/v", cfg.Verification.Links.Secret)
	incomingCodes := ratelimit.New(cfg.Verification.IncomingCodesPerPhonePerMinute, time.Minute, ratelimit.NewMemory())
	svc := core.NewService(&cfg, st, logger, lm, incomingCodes)
	api := New(&cfg, svc, logger)
	ts.Config.Handler = api.Handler()
	ts.Start()
	t.Cleanup(ts.Close)
	return ts, svc, st
}

func doJSON(t *testing.T, client *http.Client, method, url, token string, body any) (*http.Response, []byte) {
	t.Helper()
	var raw []byte
	if body != nil {
		var err error
		raw, err = json.Marshal(body)
		if err != nil {
			t.Fatal(err)
		}
	}
	req, err := http.NewRequest(method, url, bytes.NewReader(raw))
	if err != nil {
		t.Fatal(err)
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	data, err := io.ReadAll(resp.Body)
	resp.Body.Close()
	if err != nil {
		t.Fatal(err)
	}
	return resp, data
}

func TestEndToEndSMSAndPublicPage(t *testing.T) {
	ts, _, _ := newTestServer(t)
	client := ts.Client()

	resp, data := doJSON(t, client, http.MethodGet, ts.URL+"/api/v1/me", apiKey, nil)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("me: %d %s", resp.StatusCode, data)
	}

	// The first exchange registers the gateway number.
	resp, data = doJSON(t, client, http.MethodPost, ts.URL+"/exchange", gatewayToken, map[string]any{
		"sync_id": "init", gatewayNum: map[string]any{"sms": []any{}, "call": []any{}},
	})
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("exchange init: %d %s", resp.StatusCode, data)
	}

	resp, data = doJSON(t, client, http.MethodPost, ts.URL+"/api/v1/verifications", apiKey, map[string]any{
		"phone": userPhone, "method": "sms",
	})
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("create: %d %s", resp.StatusCode, data)
	}
	var created struct {
		ID     string `json:"id"`
		Code   string `json:"code"`
		Status string `json:"status"`
		Links  struct {
			PageURL string `json:"page_url"`
			QRURL   string `json:"qr_url"`
			SMSURI  string `json:"sms_uri"`
		} `json:"links"`
	}
	if err := json.Unmarshal(data, &created); err != nil {
		t.Fatal(err)
	}
	if created.ID == "" || created.Code == "" || created.Links.PageURL == "" || created.Links.SMSURI == "" {
		t.Fatalf("incomplete create response: %s", data)
	}

	// The public page must not change status on its own.
	resp, data = doJSON(t, client, http.MethodGet, created.Links.PageURL, "", nil)
	if resp.StatusCode != http.StatusOK || !strings.Contains(string(data), created.Code) {
		t.Fatalf("page: %d %s", resp.StatusCode, data)
	}
	resp, data = doJSON(t, client, http.MethodGet, created.Links.PageURL+"/status", "", nil)
	if resp.StatusCode != http.StatusOK || !strings.Contains(string(data), `"pending"`) {
		t.Fatalf("page status: %d %s", resp.StatusCode, data)
	}

	resp, data = doJSON(t, client, http.MethodPost, ts.URL+"/exchange", gatewayToken, map[string]any{
		"sync_id": "sms-1",
		gatewayNum: map[string]any{
			"sms": []map[string]any{{
				"time": time.Now().UTC().Format(time.RFC3339), "text": "Код: " + created.Code, "phone": userPhone,
			}},
		},
	})
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("exchange sms: %d %s", resp.StatusCode, data)
	}

	resp, data = doJSON(t, client, http.MethodGet, ts.URL+"/api/v1/verifications/"+created.ID, apiKey, nil)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("get: %d %s", resp.StatusCode, data)
	}
	var current struct {
		Status string `json:"status"`
		Phone  string `json:"phone"`
	}
	if err := json.Unmarshal(data, &current); err != nil {
		t.Fatal(err)
	}
	if current.Status != model.StatusVerified || current.Phone != userPhone {
		t.Fatalf("verification: %s", data)
	}

	resp, data = doJSON(t, client, http.MethodGet, created.Links.QRURL, "", nil)
	if resp.StatusCode != http.StatusOK || resp.Header.Get("Content-Type") != "image/png" {
		t.Fatalf("qr: %d %s", resp.StatusCode, data)
	}
	if len(data) < 8 || string(data[:8]) != "\x89PNG\r\n\x1a\n" {
		t.Fatal("qr is not a PNG")
	}

	resp, data = doJSON(t, client, http.MethodGet, created.Links.PageURL+"/status", "", nil)
	if resp.StatusCode != http.StatusOK || !strings.Contains(string(data), `"verified"`) {
		t.Fatalf("verified page status: %d %s", resp.StatusCode, data)
	}
}

func TestDemoPage(t *testing.T) {
	ts, _, _ := newTestServer(t)
	client := ts.Client()

	resp, data := doJSON(t, client, http.MethodGet, ts.URL+"/demo", "", nil)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("demo page: %d %s", resp.StatusCode, data)
	}
	if !strings.Contains(string(data), "PhoneGate Demo") {
		t.Fatalf("missing title: %s", data)
	}
	if !strings.Contains(string(data), "bootstrap") {
		t.Fatalf("missing bootstrap: %s", data)
	}
	if !strings.Contains(string(data), "id=\"phoneInput\"") {
		t.Fatalf("missing phone input: %s", data)
	}
	if !strings.Contains(string(data), "id=\"btnStart\"") {
		t.Fatalf("missing start button: %s", data)
	}
	if !strings.Contains(string(data), "id=\"action-sms\"") {
		t.Fatalf("missing sms action: %s", data)
	}
	if !strings.Contains(string(data), "id=\"action-call\"") {
		t.Fatalf("missing call action: %s", data)
	}
	if !strings.Contains(string(data), "id=\"timer\"") {
		t.Fatalf("missing timer: %s", data)
	}
	if !strings.Contains(string(data), "id=\"btnBack\"") {
		t.Fatalf("missing back button: %s", data)
	}
}

func TestHTTPAuthAndValidation(t *testing.T) {
	ts, _, _ := newTestServer(t)
	client := ts.Client()

	resp, _ := doJSON(t, client, http.MethodPost, ts.URL+"/api/v1/verifications", "", map[string]any{"phone": userPhone})
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("missing auth: %d", resp.StatusCode)
	}
	resp, _ = doJSON(t, client, http.MethodPost, ts.URL+"/exchange", "", map[string]any{"sync_id": "x"})
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("missing gateway auth: %d", resp.StatusCode)
	}
	resp, _ = doJSON(t, client, http.MethodPost, ts.URL+"/api/v1/verifications", apiKey, map[string]any{"phone": "bad"})
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("bad phone: %d", resp.StatusCode)
	}
	resp, _ = doJSON(t, client, http.MethodPost, ts.URL+"/api/v1/verifications", apiKey, map[string]any{"phone": userPhone, "unknown": true})
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("unknown field: %d", resp.StatusCode)
	}
	resp, _ = doJSON(t, client, http.MethodGet, ts.URL+"/v/bad-token", "", nil)
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("bad public token: %d", resp.StatusCode)
	}
}
