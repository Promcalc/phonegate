// Package config loads and validates the PhoneGate backend configuration.
package config

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"net/url"
	"os"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"gopkg.in/yaml.v3"

	"phonegate/backend/internal/phone"
)

// Duration is a YAML-friendly time.Duration ("10s", "1m").
type Duration time.Duration

func (d Duration) Std() time.Duration { return time.Duration(d) }

func (d *Duration) UnmarshalYAML(value *yaml.Node) error {
	var s string
	if err := value.Decode(&s); err != nil {
		var n int64
		if err2 := value.Decode(&n); err2 != nil {
			return fmt.Errorf("duration: %w", err)
		}
		*d = Duration(time.Duration(n) * time.Second)
		return nil
	}
	parsed, err := time.ParseDuration(strings.TrimSpace(s))
	if err != nil {
		return fmt.Errorf("duration %q: %w", s, err)
	}
	*d = Duration(parsed)
	return nil
}

type Config struct {
	Version      int                     `yaml:"version"`
	Server       ServerConfig            `yaml:"server"`
	Storage      StorageConfig           `yaml:"storage"`
	Logging      LoggingConfig           `yaml:"logging"`
	Gateway      GatewayConfig           `yaml:"gateway"`
	Verification VerificationConfig      `yaml:"verification"`
	Pools        map[string]PoolConfig   `yaml:"pools"`
	Tenants      map[string]TenantConfig `yaml:"tenants"`
	Gateways     []GatewayItem           `yaml:"gateways"`
	Admin        AdminConfig             `yaml:"admin"`
	Webhook      WebhookConfig           `yaml:"webhook"`
	Security     SecurityConfig          `yaml:"security"`
	Metrics      MetricsConfig           `yaml:"metrics"`
}

type ServerConfig struct {
	Listen         string    `yaml:"listen"`
	PublicURL      string    `yaml:"public_url"`
	ReadTimeout    Duration  `yaml:"read_timeout"`
	WriteTimeout   Duration  `yaml:"write_timeout"`
	IdleTimeout    Duration  `yaml:"idle_timeout"`
	MaxBodyBytes   int64     `yaml:"max_body_bytes"`
	TrustedProxies []string  `yaml:"trusted_proxies"`
	TLS            TLSConfig `yaml:"tls"`
}

type TLSConfig struct {
	Enabled  bool   `yaml:"enabled"`
	CertFile string `yaml:"cert_file"`
	KeyFile  string `yaml:"key_file"`
}

type StorageConfig struct {
	Driver           string          `yaml:"driver"`
	DSN              string          `yaml:"dsn"`
	MaxOpenConns     int             `yaml:"max_open_conns"`
	BusyTimeout      Duration        `yaml:"busy_timeout"`
	Retention        RetentionConfig `yaml:"retention"`
	StoreSMSText     bool            `yaml:"store_sms_text"`
	StoreRawExchange bool            `yaml:"store_raw_exchange"`
}

type RetentionConfig struct {
	SessionsDays int `yaml:"sessions_days"`
	EventsDays   int `yaml:"events_days"`
	APICallsDays int `yaml:"api_calls_days"`
	WebhookDays  int `yaml:"webhook_days"`
	AuditDays    int `yaml:"audit_days"`
}

type LoggingConfig struct {
	Level             string `yaml:"level"`
	Format            string `yaml:"format"`
	MaskPhones        bool   `yaml:"mask_phones"`
	AccessLog         bool   `yaml:"access_log"`
	LogGatewayPayload bool   `yaml:"log_gateway_payload"`
}

type GatewayConfig struct {
	AuthHeaders          []string      `yaml:"auth_headers"`
	AutoRegisterNumbers  bool          `yaml:"auto_register_numbers"`
	RequireApproval      bool          `yaml:"require_approval"`
	OfflineAfter         Duration      `yaml:"offline_after"`
	MaxNumbersPerGateway int           `yaml:"max_numbers_per_gateway"`
	AllowNumberMove      bool          `yaml:"allow_number_move"`
	MaxBodyEvents        MaxBodyEvents `yaml:"max_body_events"`
	ExchangePath         string        `yaml:"exchange_path"`
	OutgoingPath         string        `yaml:"outgoing_path"`
	// ValidatePeerNumbers rejects inbound events whose sender/caller number
	// cannot be normalized to E.164. Disable it to keep such events in the
	// journal for diagnostics (they are never matched with a verification).
	ValidatePeerNumbers bool `yaml:"validate_peer_numbers"`
}

type MaxBodyEvents struct {
	SMS   int `yaml:"sms"`
	Calls int `yaml:"calls"`
}

type VerificationConfig struct {
	DefaultTTL              Duration        `yaml:"default_ttl"`
	MaxTTL                  Duration        `yaml:"max_ttl"`
	CodeLength              int             `yaml:"code_length"`
	MaxAttempts             int             `yaml:"max_attempts"`
	RetryInterval           Duration        `yaml:"retry_interval"`
	MaxActiveSMSPerPhone    int             `yaml:"max_active_sms_per_phone"`
	OneActiveCallPerGateway bool            `yaml:"one_active_call_per_gateway"`
	Sticky                  bool            `yaml:"sticky"`
	ClockSkew               Duration        `yaml:"clock_skew"`
	Selection               string          `yaml:"selection"`
	MaxPendingPerNumber     int             `yaml:"max_pending_per_number"`
	MaxAssignmentsPerHour   int             `yaml:"max_assignments_per_hour"`
	CodeFirst               CodeFirstConfig `yaml:"code_first"`
	SMS                     SMSConfig       `yaml:"sms"`
	Call                    CallConfig      `yaml:"call"`
	Links                   LinksConfig     `yaml:"links"`
}

type CodeFirstConfig struct {
	Enabled               bool     `yaml:"enabled"`
	TTL                   Duration `yaml:"ttl"`
	CodeFormat            string   `yaml:"code_format"`
	CodeLength            int      `yaml:"code_length"`
	CodeAlphabet          string   `yaml:"code_alphabet"`
	CodeWords             int      `yaml:"code_words"`
	MaxActivePerTenant    int      `yaml:"max_active_per_tenant"`
	MaxWrongCodesPerPhone int      `yaml:"max_wrong_codes_per_phone"`
	WrongCodesWindow      Duration `yaml:"wrong_codes_window"`
}

type SMSConfig struct {
	CodeRegex string `yaml:"code_regex"`
}

type CallConfig struct {
	AllowMissed        bool `yaml:"allow_missed"`
	AllowAnswered      bool `yaml:"allow_answered"`
	MinDurationSeconds int  `yaml:"min_duration_seconds"`
}

type LinksConfig struct {
	Enabled           bool   `yaml:"enabled"`
	Secret            string `yaml:"secret"`
	PagePath          string `yaml:"page_path"`
	QRSize            int    `yaml:"qr_size"`
	QRMargin          int    `yaml:"qr_margin"`
	QRErrorCorrection string `yaml:"qr_error_correction"`
	RateLimitPerMin   int    `yaml:"rate_limit_per_min"`
}

type PoolConfig struct {
	Numbers []string `yaml:"numbers"`
}

type TenantConfig struct {
	Name             string             `yaml:"name"`
	Enabled          bool               `yaml:"enabled"`
	Pools            []string           `yaml:"pools"`
	AllowedCountries []string           `yaml:"allowed_countries"`
	Auth             TenantAuth         `yaml:"auth"`
	Verification     TenantVerification `yaml:"verification"`
	Webhook          TenantWebhook      `yaml:"webhook"`
	RateLimits       RateLimits         `yaml:"rate_limits"`
}

type TenantAuth struct {
	APIKeySHA256         string   `yaml:"api_key_sha256"`
	APIKeySHA256Previous string   `yaml:"api_key_sha256_previous"`
	IPAllowlist          []string `yaml:"ip_allowlist"`
	HMACSecret           string   `yaml:"hmac_secret"`
}

type TenantVerification struct {
	Methods       []string `yaml:"methods"`
	DefaultMethod string   `yaml:"default_method"`
	TTL           Duration `yaml:"ttl"`
	CodeLength    int      `yaml:"code_length"`
	MaxAttempts   int      `yaml:"max_attempts"`
}

type TenantWebhook struct {
	URL                 string   `yaml:"url"`
	Secret              string   `yaml:"secret"`
	Events              []string `yaml:"events"`
	Timeout             Duration `yaml:"timeout"`
	AllowedCallbackURLs []string `yaml:"allowed_callback_urls"`
}

type RateLimitConfig struct {
	Requests int      `yaml:"requests"`
	Window   Duration `yaml:"window"`
}

type RateLimits struct {
	PerPhone  RateLimitConfig `yaml:"per_phone"`
	PerIP     RateLimitConfig `yaml:"per_ip"`
	PerTenant RateLimitConfig `yaml:"per_tenant"`
}

type GatewayItem struct {
	ID          string `yaml:"id"`
	Name        string `yaml:"name"`
	TokenSHA256 string `yaml:"token_sha256"`
	Pool        string `yaml:"pool"`
	Enabled     bool   `yaml:"enabled"`
}

type AdminConfig struct {
	Enabled     bool     `yaml:"enabled"`
	TokenSHA256 string   `yaml:"token_sha256"`
	IPAllowlist []string `yaml:"ip_allowlist"`
	Listen      string   `yaml:"listen"`
}

type WebhookConfig struct {
	AllowPrivate  bool     `yaml:"allow_private"`
	AllowInsecure bool     `yaml:"allow_insecure"`
	MaxAttempts   int      `yaml:"max_attempts"`
	Backoff       []string `yaml:"backoff"`
	Workers       int      `yaml:"workers"`
}

type SecurityConfig struct {
	GlobalIPRPS    int      `yaml:"global_ip_rps"`
	RequireHTTPS   bool     `yaml:"require_https"`
	MaxJSONDepth   int      `yaml:"max_json_depth"`
	IdempotencyTTL Duration `yaml:"idempotency_ttl"`
	HMACSkew       Duration `yaml:"hmac_skew"`
}

type MetricsConfig struct {
	Enabled bool   `yaml:"enabled"`
	Listen  string `yaml:"listen"`
	Path    string `yaml:"path"`
}

// Defaults returns a configuration with safe development defaults. YAML
// fields override them; validation still requires real secrets.
func Defaults() Config {
	return Config{
		Version: 1,
		Server: ServerConfig{
			Listen:       ":8080",
			PublicURL:    "http://localhost:8080",
			ReadTimeout:  Duration(10 * time.Second),
			WriteTimeout: Duration(15 * time.Second),
			IdleTimeout:  Duration(60 * time.Second),
			MaxBodyBytes: 1 << 20,
		},
		Storage: StorageConfig{
			Driver:       "memory",
			MaxOpenConns: 10,
			BusyTimeout:  Duration(5 * time.Second),
			Retention: RetentionConfig{
				SessionsDays: 30, EventsDays: 7, APICallsDays: 7,
				WebhookDays: 30, AuditDays: 365,
			},
		},
		Logging: LoggingConfig{Level: "info", Format: "json", MaskPhones: true, AccessLog: true},
		Gateway: GatewayConfig{
			AuthHeaders:          []string{"Authorization"},
			AutoRegisterNumbers:  true,
			RequireApproval:      true,
			OfflineAfter:         Duration(90 * time.Second),
			MaxNumbersPerGateway: 4,
			MaxBodyEvents:        MaxBodyEvents{SMS: 500, Calls: 1000},
			ExchangePath:         "/exchange",
			OutgoingPath:         "/outgoing-report",
			ValidatePeerNumbers:  true,
		},
		Verification: VerificationConfig{
			DefaultTTL:              Duration(5 * time.Minute),
			MaxTTL:                  Duration(15 * time.Minute),
			CodeLength:              6,
			MaxAttempts:             3,
			RetryInterval:           Duration(30 * time.Second),
			MaxActiveSMSPerPhone:    10,
			OneActiveCallPerGateway: true,
			Sticky:                  true,
			ClockSkew:               Duration(120 * time.Second),
			Selection:               "least_loaded",
			MaxPendingPerNumber:     50,
			MaxAssignmentsPerHour:   120,
			CodeFirst: CodeFirstConfig{
				Enabled:               true,
				TTL:                   Duration(10 * time.Minute),
				CodeFormat:            "alnum",
				CodeLength:            8,
				CodeAlphabet:          "ABCDEFGHJKLMNPQRSTUVWXYZ23456789",
				CodeWords:             4,
				MaxActivePerTenant:    1000,
				MaxWrongCodesPerPhone: 5,
				WrongCodesWindow:      Duration(time.Hour),
			},
			SMS:  SMSConfig{CodeRegex: `(?i)(?:^|\D)([0-9]{6})(?:\D|$)`},
			Call: CallConfig{AllowMissed: true, AllowAnswered: true},
			Links: LinksConfig{
				Enabled:           true,
				PagePath:          "/v",
				QRSize:            320,
				QRMargin:          2,
				QRErrorCorrection: "medium",
				RateLimitPerMin:   60,
			},
		},
		Webhook: WebhookConfig{
			MaxAttempts: 5,
			Backoff:     []string{"1s", "5s", "30s", "2m", "10m"},
			Workers:     8,
		},
		Security: SecurityConfig{
			GlobalIPRPS:    50,
			RequireHTTPS:   true,
			MaxJSONDepth:   32,
			IdempotencyTTL: Duration(24 * time.Hour),
			HMACSkew:       Duration(5 * time.Minute),
		},
		Metrics: MetricsConfig{Path: "/metrics"},
	}
}

var envRe = regexp.MustCompile(`\$\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?\}`)

// expandEnv substitutes ${VAR} and ${VAR:-default}. Missing variables without
// a default become an empty string, which validation will reject for secrets.
func expandEnv(s string) string {
	return envRe.ReplaceAllStringFunc(s, func(m string) string {
		parts := envRe.FindStringSubmatch(m)
		if parts == nil {
			return m
		}
		if value, ok := os.LookupEnv(parts[1]); ok {
			return value
		}
		return parts[2]
	})
}

// Load reads YAML, expands environment variables, applies defaults and
// validates the result. Unknown fields are rejected.
func Load(path string) (*Config, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("read config: %w", err)
	}
	raw = []byte(expandEnv(string(raw)))

	cfg := Defaults()
	dec := yaml.NewDecoder(strings.NewReader(string(raw)))
	dec.KnownFields(true)
	if err := dec.Decode(&cfg); err != nil {
		return nil, fmt.Errorf("parse config: %w", err)
	}
	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	return &cfg, nil
}

// Parse loads from bytes (used by tests).
func Parse(raw []byte) (*Config, error) {
	cfg := Defaults()
	dec := yaml.NewDecoder(strings.NewReader(expandEnv(string(raw))))
	dec.KnownFields(true)
	if err := dec.Decode(&cfg); err != nil {
		return nil, fmt.Errorf("parse config: %w", err)
	}
	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	return &cfg, nil
}

func (c *Config) Validate() error {
	if c.Version != 1 {
		return fmt.Errorf("version must be 1")
	}
	if strings.TrimSpace(c.Server.Listen) == "" {
		return fmt.Errorf("server.listen is required")
	}
	if c.Server.MaxBodyBytes <= 0 {
		return fmt.Errorf("server.max_body_bytes must be positive")
	}
	if c.Server.PublicURL == "" {
		return fmt.Errorf("server.public_url is required")
	}
	u, err := url.Parse(c.Server.PublicURL)
	if err != nil || u.Scheme == "" || u.Host == "" {
		return fmt.Errorf("server.public_url must be an absolute URL")
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return fmt.Errorf("server.public_url must use http or https")
	}
	switch c.Storage.Driver {
	case "memory", "sqlite", "postgres":
	default:
		return fmt.Errorf("storage.driver must be memory, sqlite or postgres")
	}
	switch c.Logging.Format {
	case "", "json", "text":
	default:
		return fmt.Errorf("logging.format must be json or text")
	}
	if len(c.Gateway.AuthHeaders) == 0 {
		return fmt.Errorf("gateway.auth_headers must not be empty")
	}
	if c.Gateway.ExchangePath == "" || !strings.HasPrefix(c.Gateway.ExchangePath, "/") {
		return fmt.Errorf("gateway.exchange_path must start with /")
	}
	if c.Gateway.OutgoingPath == "" || !strings.HasPrefix(c.Gateway.OutgoingPath, "/") {
		return fmt.Errorf("gateway.outgoing_path must start with /")
	}
	if c.Gateway.OfflineAfter <= 0 {
		return fmt.Errorf("gateway.offline_after must be positive")
	}
	if c.Gateway.MaxBodyEvents.SMS <= 0 || c.Gateway.MaxBodyEvents.Calls <= 0 {
		return fmt.Errorf("gateway.max_body_events must be positive")
	}
	if c.Verification.DefaultTTL.Std() < 60*time.Second || c.Verification.DefaultTTL.Std() > c.Verification.MaxTTL.Std() {
		return fmt.Errorf("verification.default_ttl must be between 60s and max_ttl")
	}
	if c.Verification.MaxTTL.Std() > 15*time.Minute {
		return fmt.Errorf("verification.max_ttl must be <= 15m")
	}
	if c.Verification.CodeLength < 4 || c.Verification.CodeLength > 12 {
		return fmt.Errorf("verification.code_length must be 4..12")
	}
	if c.Verification.MaxAttempts < 1 {
		return fmt.Errorf("verification.max_attempts must be positive")
	}
	if c.Verification.MaxActiveSMSPerPhone < 1 {
		return fmt.Errorf("verification.max_active_sms_per_phone must be positive")
	}
	if c.Verification.MaxPendingPerNumber < 1 {
		return fmt.Errorf("verification.max_pending_per_number must be positive")
	}
	switch c.Verification.Selection {
	case "least_loaded", "round_robin", "random":
	default:
		return fmt.Errorf("verification.selection must be least_loaded, round_robin or random")
	}
	if c.Verification.CodeFirst.Enabled {
		if c.Verification.CodeFirst.CodeLength < 4 || c.Verification.CodeFirst.CodeLength > 32 {
			return fmt.Errorf("verification.code_first.code_length must be 4..32")
		}
		switch c.Verification.CodeFirst.CodeFormat {
		case "alnum", "digits", "words":
		default:
			return fmt.Errorf("verification.code_first.code_format must be alnum, digits or words")
		}
		if c.Verification.CodeFirst.CodeFormat != "digits" {
			if len([]rune(c.Verification.CodeFirst.CodeAlphabet)) < 16 {
				return fmt.Errorf("verification.code_first.code_alphabet must contain at least 16 symbols")
			}
		}
	}
	if c.Verification.CodeFirst.Enabled && (c.Verification.CodeFirst.TTL.Std() <= 0 || c.Verification.CodeFirst.TTL.Std() > c.Verification.MaxTTL.Std()) {
		return fmt.Errorf("verification.code_first.ttl must be between 1s and max_ttl")
	}
	if c.Verification.Links.Enabled {
		if len(c.Verification.Links.Secret) < 32 {
			return fmt.Errorf("verification.links.secret must be at least 32 bytes")
		}
		if c.Verification.Links.PagePath == "" || !strings.HasPrefix(c.Verification.Links.PagePath, "/") {
			return fmt.Errorf("verification.links.page_path must start with /")
		}
		if c.Verification.Links.QRSize < 64 || c.Verification.Links.QRSize > 2048 {
			return fmt.Errorf("verification.links.qr_size must be 64..2048")
		}
		if c.Verification.Links.QRMargin < 0 || c.Verification.Links.QRMargin > 16 {
			return fmt.Errorf("verification.links.qr_margin must be 0..16")
		}
		if _, err := qrErrorLevel(c.Verification.Links.QRErrorCorrection); err != nil {
			return err
		}
	}

	// Normalize and validate pools.
	seenNumbers := make(map[string]string)
	for name, pool := range c.Pools {
		if name == "" {
			return fmt.Errorf("pool name must not be empty")
		}
		normalized := make([]string, 0, len(pool.Numbers))
		for _, raw := range pool.Numbers {
			n, err := phone.Normalize(raw)
			if err != nil {
				return fmt.Errorf("pool %q: invalid number %q: %w", name, raw, err)
			}
			if other, ok := seenNumbers[n]; ok {
				return fmt.Errorf("number %s appears in pools %q and %q", n, other, name)
			}
			seenNumbers[n] = name
			normalized = append(normalized, n)
		}
		pool.Numbers = normalized
		c.Pools[name] = pool
	}

	if len(c.Tenants) == 0 {
		return fmt.Errorf("at least one tenant is required")
	}
	apiKeyOwner := make(map[string]string)
	poolOwner := make(map[string]string)
	for id, tenant := range c.Tenants {
		if id == "" {
			return fmt.Errorf("tenant id must not be empty")
		}
		if !tenant.Enabled {
			continue
		}
		if !validSHA256Hex(tenant.Auth.APIKeySHA256) {
			return fmt.Errorf("tenant %q: auth.api_key_sha256 must be 64 hex characters", id)
		}
		if tenant.Auth.APIKeySHA256Previous != "" && !validSHA256Hex(tenant.Auth.APIKeySHA256Previous) {
			return fmt.Errorf("tenant %q: auth.api_key_sha256_previous must be 64 hex characters", id)
		}
		apiHash := strings.ToLower(tenant.Auth.APIKeySHA256)
		if owner, ok := apiKeyOwner[apiHash]; ok {
			return fmt.Errorf("tenant %q: api key hash is already used by tenant %q", id, owner)
		}
		apiKeyOwner[apiHash] = id
		tenant.Auth.APIKeySHA256 = apiHash
		if tenant.Auth.APIKeySHA256Previous != "" {
			previous := strings.ToLower(tenant.Auth.APIKeySHA256Previous)
			if owner, ok := apiKeyOwner[previous]; ok && owner != id {
				return fmt.Errorf("tenant %q: previous api key hash is already used by tenant %q", id, owner)
			}
			apiKeyOwner[previous] = id
			tenant.Auth.APIKeySHA256Previous = previous
		}
		if len(tenant.Pools) == 0 {
			return fmt.Errorf("tenant %q: pools must not be empty", id)
		}
		for _, pool := range tenant.Pools {
			if _, ok := c.Pools[pool]; !ok {
				return fmt.Errorf("tenant %q: unknown pool %q", id, pool)
			}
			if owner, ok := poolOwner[pool]; ok && owner != id {
				return fmt.Errorf("pool %q is shared by tenants %q and %q", pool, owner, id)
			}
			poolOwner[pool] = id
		}
		if tenant.Verification.TTL == 0 {
			tenant.Verification.TTL = c.Verification.DefaultTTL
		}
		if tenant.Verification.TTL.Std() < 60*time.Second || tenant.Verification.TTL.Std() > c.Verification.MaxTTL.Std() {
			return fmt.Errorf("tenant %q: verification.ttl must be between 60s and verif.max_ttl", id)
		}
		if tenant.Verification.CodeLength == 0 {
			tenant.Verification.CodeLength = c.Verification.CodeLength
		}
		if tenant.Verification.CodeLength < 4 || tenant.Verification.CodeLength > 12 {
			return fmt.Errorf("tenant %q: verification.code_length must be 4..12", id)
		}
		if tenant.Verification.MaxAttempts == 0 {
			tenant.Verification.MaxAttempts = c.Verification.MaxAttempts
		}
		if len(tenant.Verification.Methods) == 0 {
			tenant.Verification.Methods = []string{modelMethodSMS}
		}
		for _, m := range tenant.Verification.Methods {
			if m != "sms" && m != "call" && m != "any" {
				return fmt.Errorf("tenant %q: invalid verification method %q", id, m)
			}
		}
		if tenant.Verification.DefaultMethod == "" {
			tenant.Verification.DefaultMethod = tenant.Verification.Methods[0]
		}
		if !contains(tenant.Verification.Methods, tenant.Verification.DefaultMethod) {
			return fmt.Errorf("tenant %q: default_method %q is not in methods", id, tenant.Verification.DefaultMethod)
		}
		if tenant.Webhook.Timeout == 0 {
			tenant.Webhook.Timeout = Duration(10 * time.Second)
		}
		c.Tenants[id] = tenant
	}

	seenGateways := make(map[string]struct{})
	tokenOwner := make(map[string]string)
	for i, gw := range c.Gateways {
		if gw.ID == "" {
			return fmt.Errorf("gateway id must not be empty")
		}
		if _, ok := seenGateways[gw.ID]; ok {
			return fmt.Errorf("duplicate gateway id %q", gw.ID)
		}
		seenGateways[gw.ID] = struct{}{}
		if !validSHA256Hex(gw.TokenSHA256) {
			return fmt.Errorf("gateway %q: token_sha256 must be 64 hex characters", gw.ID)
		}
		tokenHash := strings.ToLower(gw.TokenSHA256)
		if owner, ok := tokenOwner[tokenHash]; ok {
			return fmt.Errorf("gateway %q: token is already used by gateway %q", gw.ID, owner)
		}
		tokenOwner[tokenHash] = gw.ID
		gw.TokenSHA256 = tokenHash
		if gw.Pool != "" {
			if _, ok := c.Pools[gw.Pool]; !ok {
				return fmt.Errorf("gateway %q: unknown pool %q", gw.ID, gw.Pool)
			}
		}
		c.Gateways[i] = gw
	}
	if c.Admin.Enabled && c.Admin.TokenSHA256 != "" && !validSHA256Hex(c.Admin.TokenSHA256) {
		return fmt.Errorf("admin.token_sha256 must be 64 hex characters")
	}
	switch c.Logging.Level {
	case "", "debug", "info", "warn", "error":
	default:
		return fmt.Errorf("logging.level must be debug, info, warn or error")
	}
	return nil
}

const modelMethodSMS = "sms"

func contains(values []string, value string) bool {
	for _, v := range values {
		if v == value {
			return true
		}
	}
	return false
}

func validSHA256Hex(value string) bool {
	if len(value) != 64 {
		return false
	}
	_, err := hex.DecodeString(value)
	return err == nil
}

// HashSecret returns the lowercase hex SHA-256 used by the configuration.
func HashSecret(secret string) string {
	sum := sha256.Sum256([]byte(secret))
	return hex.EncodeToString(sum[:])
}

// TenantIDs returns sorted tenant ids.
func (c *Config) TenantIDs() []string {
	ids := make([]string, 0, len(c.Tenants))
	for id := range c.Tenants {
		if c.Tenants[id].Enabled {
			ids = append(ids, id)
		}
	}
	sort.Strings(ids)
	return ids
}

// Tenant returns a tenant by id.
func (c *Config) Tenant(id string) (TenantConfig, bool) {
	t, ok := c.Tenants[id]
	return t, ok && t.Enabled
}

// GatewayByID returns a gateway by id.
func (c *Config) GatewayByID(id string) (GatewayItem, bool) {
	for _, gw := range c.Gateways {
		if gw.ID == id {
			return gw, true
		}
	}
	return GatewayItem{}, false
}

// PoolTenant maps every pool to its tenant.
func (c *Config) PoolTenant() map[string]string {
	out := make(map[string]string)
	for id, tenant := range c.Tenants {
		if !tenant.Enabled {
			continue
		}
		for _, pool := range tenant.Pools {
			out[pool] = id
		}
	}
	return out
}

// PoolNumbers returns the configured numbers for a pool.
func (c *Config) PoolNumbers(pool string) []string {
	p, ok := c.Pools[pool]
	if !ok {
		return nil
	}
	return append([]string(nil), p.Numbers...)
}

// IsConfiguredPoolNumber reports whether e164 is explicitly listed in a pool.
func (c *Config) IsConfiguredPoolNumber(e164 string) bool {
	for _, pool := range c.Pools {
		for _, n := range pool.Numbers {
			if n == e164 {
				return true
			}
		}
	}
	return false
}

// QRSize returns the configured QR size with a safe fallback.
func (c *Config) QRSize() int {
	if c.Verification.Links.QRSize > 0 {
		return c.Verification.Links.QRSize
	}
	return 320
}

// QRMargin returns the configured QR margin.
func (c *Config) QRMargin() int { return c.Verification.Links.QRMargin }

// QRErrorLevel returns a normalized QR error correction level.
func (c *Config) QRErrorLevel() string {
	return strings.ToLower(c.Verification.Links.QRErrorCorrection)
}

func qrErrorLevel(value string) (string, error) {
	switch strings.ToLower(value) {
	case "", "medium":
		return "medium", nil
	case "low", "high", "highest":
		return strings.ToLower(value), nil
	default:
		return "", fmt.Errorf("verification.links.qr_error_correction must be low, medium, high or highest")
	}
}

// ParseInt is a small helper for YAML/CLI values.
func ParseInt(value string, fallback int) int {
	n, err := strconv.Atoi(value)
	if err != nil {
		return fallback
	}
	return n
}
