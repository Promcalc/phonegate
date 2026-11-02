package core

import (
	"crypto/rand"
	"encoding/base32"
	"encoding/json"
	"strings"
	"time"

	"phonegate/backend/internal/model"
)

type CreateRequest struct {
	Mode        string         `json:"mode"`
	Phone       string         `json:"phone"`
	Method      string         `json:"method"`
	Purpose     string         `json:"purpose"`
	Locale      string         `json:"locale"`
	TTLSeconds  int            `json:"ttl_seconds"`
	Metadata    map[string]any `json:"metadata"`
	CallbackURL string         `json:"callback_url"`
}

type LinkSet struct {
	SMSURI    *string `json:"sms_uri"`
	SMSURIIOS *string `json:"sms_uri_ios"`
	TelURI    *string `json:"tel_uri"`
	PageURL   string  `json:"page_url"`
	QRURL     string  `json:"qr_url"`
	// SMSQRURL and TelQRURL encode an HTTPS landing page instead of a raw
	// sms:/tel: URI. Android camera/QR scanners often show a raw sms: URI as
	// plain text, while an HTTPS link always opens and offers the action.
	SMSQRURL *string `json:"sms_qr_url"`
	TelQRURL *string `json:"tel_qr_url"`
}

type Created struct {
	Verification *model.Verification
	Code         string
	Links        *LinkSet
	Instructions string
	PollAfterMS  int
}

type VerificationResponse struct {
	ID            string         `json:"id"`
	Mode          string         `json:"mode"`
	Status        string         `json:"status"`
	Method        string         `json:"method"`
	Phone         *string        `json:"phone"`
	GatewayNumber string         `json:"gateway_number"`
	Code          string         `json:"code,omitempty"`
	Instructions  string         `json:"instructions,omitempty"`
	CreatedAt     time.Time      `json:"created_at"`
	ExpiresAt     time.Time      `json:"expires_at"`
	VerifiedAt    *time.Time     `json:"verified_at"`
	Reason        *string        `json:"reason"`
	PollAfterMS   int            `json:"poll_after_ms"`
	Links         *LinkSet       `json:"links,omitempty"`
	Metadata      map[string]any `json:"metadata,omitempty"`
}

type ListResponse struct {
	Items      []*VerificationResponse `json:"items"`
	NextCursor *string                 `json:"next_cursor"`
}

type TenantInfo struct {
	ID      string         `json:"id"`
	Name    string         `json:"name"`
	Enabled bool           `json:"enabled"`
	Pools   []string       `json:"pools"`
	Methods []string       `json:"methods"`
	Modes   []string       `json:"modes"`
	Limits  map[string]any `json:"limits"`
}

type ExchangeRequest struct {
	SyncID string
	Sims   []SimReport
}

type SimReport struct {
	Phone string
	SMS   []SMSItem
	Call  []CallItem
}

type SMSItem struct {
	Time  string `json:"time"`
	Text  string `json:"text"`
	Phone string `json:"phone"`
}

type CallItem struct {
	Time      string `json:"time"`
	Text      string `json:"text"`
	Phone     string `json:"phone"`
	Duration  int    `json:"duration"`
	Direction string `json:"direction"`
}

// ParseExchange decodes the dynamic top-level map used by the Android app.
func ParseExchange(raw map[string]json.RawMessage, maxSMS, maxCalls int) (*ExchangeRequest, error) {
	req := &ExchangeRequest{}
	for key, value := range raw {
		if key == "sync_id" {
			if err := json.Unmarshal(value, &req.SyncID); err != nil {
				return nil, errBad("invalid_request", "Некорректный sync_id")
			}
			continue
		}
		if !strings.HasPrefix(key, "+") && !strings.HasPrefix(key, "7") && !strings.HasPrefix(key, "8") {
			// Unknown top-level field: ignore for forward compatibility.
			continue
		}
		var payload struct {
			SMS  []SMSItem  `json:"sms"`
			Call []CallItem `json:"call"`
		}
		if err := json.Unmarshal(value, &payload); err != nil {
			// Ignore a malformed per-SIM section rather than blocking the
			// whole exchange; the next window can retry it.
			continue
		}
		if maxSMS > 0 && len(payload.SMS) > maxSMS {
			payload.SMS = payload.SMS[:maxSMS]
		}
		if maxCalls > 0 && len(payload.Call) > maxCalls {
			payload.Call = payload.Call[:maxCalls]
		}
		req.Sims = append(req.Sims, SimReport{Phone: key, SMS: payload.SMS, Call: payload.Call})
	}
	return req, nil
}

func NewID(prefix string) string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		// crypto/rand should never fail; fall back to a timestamp so the
		// process does not panic in impossible environments.
		return prefix + strings.ToLower(base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString([]byte(time.Now().UTC().Format(time.RFC3339Nano))))
	}
	enc := base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString(b[:])
	return prefix + strings.ToLower(enc)
}

func cloneCreated(c *Created) *Created {
	if c == nil {
		return nil
	}
	out := *c
	out.Verification = cloneVerification(c.Verification)
	if c.Links != nil {
		l := *c.Links
		out.Links = &l
	}
	return &out
}

func cloneVerification(v *model.Verification) *model.Verification {
	if v == nil {
		return nil
	}
	out := *v
	if v.Phone != nil {
		p := *v.Phone
		out.Phone = &p
	}
	if v.VerifiedAt != nil {
		t := *v.VerifiedAt
		out.VerifiedAt = &t
	}
	if v.CodeHash != nil {
		out.CodeHash = append([]byte(nil), v.CodeHash...)
	}
	if v.Metadata != nil {
		out.Metadata = make(map[string]any, len(v.Metadata))
		for k, val := range v.Metadata {
			out.Metadata[k] = val
		}
	}
	return &out
}
