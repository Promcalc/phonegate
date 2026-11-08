package config

import (
	"os"
	"strings"
	"testing"
)

func TestLoadDevelopmentExample(t *testing.T) {
	cfg, err := Load("../../config.example.yaml")
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Storage.Driver != "memory" {
		t.Fatalf("driver = %s", cfg.Storage.Driver)
	}
	if _, ok := cfg.Tenant("demo"); !ok {
		t.Fatal("demo tenant missing")
	}
	if len(cfg.Pools["demo"].Numbers) != 1 || cfg.Pools["demo"].Numbers[0] != "+79005550001" {
		t.Fatalf("pool: %#v", cfg.Pools["demo"])
	}
}

func TestIncomingCodeRateLimitConfig(t *testing.T) {
	if got := Defaults().Verification.IncomingCodesPerPhonePerMinute; got != 10 {
		t.Fatalf("default incoming code limit = %d", got)
	}
	example, err := os.ReadFile("../../config.example.yaml")
	if err != nil {
		t.Fatal(err)
	}
	for _, value := range []string{"0", "-1", "2"} {
		t.Run(value, func(t *testing.T) {
			cfg, err := Parse([]byte(strings.Replace(string(example), "incoming_codes_per_phone_per_minute: 10", "incoming_codes_per_phone_per_minute: "+value, 1)))
			if value == "2" {
				if err != nil {
					t.Fatal(err)
				}
				if cfg.Verification.IncomingCodesPerPhonePerMinute != 2 {
					t.Fatal("configured limit was not loaded")
				}
			} else if err == nil || !strings.Contains(err.Error(), "incoming_codes_per_phone_per_minute must be positive") {
				t.Fatalf("expected invalid incoming code limit, got %v", err)
			}
		})
	}
}

func TestRejectsUnknownFields(t *testing.T) {
	_, err := Parse([]byte(`
version: 1
server:
  public_url: https://auth.example.test
  listen: ":8080"
verification:
  links:
    enabled: false
unknown: true
`))
	if err == nil || !strings.Contains(err.Error(), "field unknown") {
		t.Fatalf("expected unknown field error, got %v", err)
	}
}

func TestRejectsSharedPool(t *testing.T) {
	_, err := Parse([]byte(`
version: 1
server:
  public_url: https://auth.example.test
  listen: ":8080"
verification:
  links:
    enabled: false
pools:
  p: {numbers: ["+79005550001"]}
tenants:
  a:
    enabled: true
    pools: [p]
    auth: {api_key_sha256: "` + strings.Repeat("a", 64) + `"}
  b:
    enabled: true
    pools: [p]
    auth: {api_key_sha256: "` + strings.Repeat("b", 64) + `"}
gateways:
  - id: g1
    token_sha256: "` + strings.Repeat("c", 64) + `"
    pool: p
    enabled: true
`))
	if err == nil || !strings.Contains(err.Error(), "shared") {
		t.Fatalf("expected shared pool error, got %v", err)
	}
}

func TestRejectsDuplicateGatewayToken(t *testing.T) {
	token := strings.Repeat("c", 64)
	_, err := Parse([]byte(`
version: 1
server:
  public_url: https://auth.example.test
  listen: ":8080"
verification:
  links:
    enabled: false
pools:
  p: {numbers: ["+79005550001"]}
tenants:
  a:
    enabled: true
    pools: [p]
    auth: {api_key_sha256: "` + strings.Repeat("a", 64) + `"}
gateways:
  - {id: g1, token_sha256: "` + token + `", pool: p, enabled: true}
  - {id: g2, token_sha256: "` + token + `", pool: p, enabled: true}
`))
	if err == nil || !strings.Contains(err.Error(), "already used") {
		t.Fatalf("expected duplicate token error, got %v", err)
	}
}

func TestExpandEnv(t *testing.T) {
	t.Setenv("PHONEGATE_TEST_SECRET", "secret-value")
	if got := expandEnv("a=${PHONEGATE_TEST_SECRET} b=${MISSING:-fallback}"); got != "a=secret-value b=fallback" {
		t.Fatalf("expanded: %q", got)
	}
}

func TestHashSecret(t *testing.T) {
	if got := HashSecret("test-tenant-key"); got != "6f60060add40212a54118c66e4ebe05f399494428d155bfecf472cf544313f71" {
		t.Fatalf("hash = %s", got)
	}
}
