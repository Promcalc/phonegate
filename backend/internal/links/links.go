// Package links creates and verifies the HMAC-signed public page tokens.
package links

import (
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/url"
	"strings"
	"time"
)

var (
	ErrInvalid = errors.New("invalid link token")
	ErrExpired = errors.New("link expired")
)

type Payload struct {
	Version int    `json:"v"`
	Session string `json:"sid"`
	Code    string `json:"code,omitempty"`
	Expires int64  `json:"exp"`
}

type Token struct {
	Value   string
	PageURL string
	QRURL   string
}

type Manager struct {
	secret    []byte
	publicURL string
	pagePath  string
	now       func() time.Time
}

func NewManager(publicURL, pagePath, secret string) *Manager {
	return &Manager{
		secret:    []byte(secret),
		publicURL: strings.TrimRight(publicURL, "/"),
		pagePath:  "/" + strings.Trim(strings.TrimSpace(pagePath), "/"),
		now:       func() time.Time { return time.Now().UTC() },
	}
}

func (m *Manager) Enabled() bool { return len(m.secret) > 0 }

// SetClock is used by tests.
func (m *Manager) SetClock(fn func() time.Time) {
	if fn != nil {
		m.now = fn
	}
}

// New signs a page token for a verification. The code is part of the bearer
// token because the public page must show it; it is never put in logs.
func (m *Manager) New(sessionID, code string, expires time.Time) (*Token, error) {
	if !m.Enabled() {
		return nil, ErrInvalid
	}
	payload := Payload{
		Version: 1,
		Session: sessionID,
		Code:    code,
		Expires: expires.UTC().Unix(),
	}
	raw, err := json.Marshal(payload)
	if err != nil {
		return nil, err
	}
	body := base64.RawURLEncoding.EncodeToString(raw)
	sig := m.sign([]byte(body))
	value := "vt_" + body + "." + base64.RawURLEncoding.EncodeToString(sig)
	page := m.publicURL + m.pagePath + "/" + value
	return &Token{Value: value, PageURL: page, QRURL: page + "/qr.png"}, nil
}

func (m *Manager) sign(body []byte) []byte {
	mac := hmac.New(sha256.New, m.secret)
	_, _ = mac.Write(body)
	return mac.Sum(nil)
}

// Parse verifies signature and expiry before any database lookup.
func (m *Manager) Parse(value string) (*Payload, error) {
	if !m.Enabled() {
		return nil, ErrInvalid
	}
	value = strings.TrimSpace(value)
	value = strings.TrimPrefix(value, "vt_")
	parts := strings.Split(value, ".")
	if len(parts) != 2 || parts[0] == "" || parts[1] == "" {
		return nil, ErrInvalid
	}
	body, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil {
		return nil, ErrInvalid
	}
	sig, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return nil, ErrInvalid
	}
	expected := m.sign([]byte(parts[0]))
	if subtle.ConstantTimeCompare(sig, expected) != 1 {
		return nil, ErrInvalid
	}
	var payload Payload
	if err := json.Unmarshal(body, &payload); err != nil {
		return nil, ErrInvalid
	}
	if payload.Version != 1 || payload.Session == "" || payload.Expires <= 0 {
		return nil, ErrInvalid
	}
	if !m.now().Before(time.Unix(payload.Expires, 0)) {
		return nil, ErrExpired
	}
	return &payload, nil
}

// PageURL returns the canonical page URL for an already signed token.
func (m *Manager) PageURL(value string) string {
	return m.publicURL + m.pagePath + "/" + value
}

// QRURL returns the QR URL for an already signed token.
func (m *Manager) QRURL(value string) string {
	return m.PageURL(value) + "/qr.png"
}

// ActionURL returns the small HTTPS landing page that opens sms:/tel: on the
// phone. QR scanners reliably open HTTPS links, unlike raw custom schemes.
func (m *Manager) ActionURL(value, action string) string {
	return m.PageURL(value) + "/go/" + action
}

// EscapeBody encodes a code for an sms: URI body.
func EscapeBody(code string) string {
	return url.QueryEscape(code)
}
