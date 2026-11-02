// Package model contains the domain types shared by the store and the service.
package model

import "time"

const (
	ModePhoneFirst = "phone_first"
	ModeCodeFirst  = "code_first"

	MethodSMS  = "sms"
	MethodCall = "call"
	MethodAny  = "any"

	StatusPending   = "pending"
	StatusVerified  = "verified"
	StatusExpired   = "expired"
	StatusCancelled = "cancelled"
	StatusFailed    = "failed"

	ReasonTooManyAttempts = "too_many_attempts"
	ReasonGatewayError    = "gateway_error"
	ReasonRejected        = "rejected"

	NumberPending  = "pending"
	NumberActive   = "active"
	NumberDisabled = "disabled"

	GatewayActive   = "active"
	GatewayDisabled = "disabled"
	GatewayPending  = "pending"

	DirectionIn     = "in"
	DirectionOut    = "out"
	DirectionMissed = "missed"

	EventSMS  = "sms"
	EventCall = "call"
)

// Verification is one authorization request.
type Verification struct {
	ID            string
	TenantID      string
	Mode          string
	Status        string
	Method        string
	Phone         *string
	GatewayID     string
	GatewayNumber string
	Pool          string
	CodeHash      []byte
	Purpose       string
	Locale        string
	Metadata      map[string]any
	CallbackURL   string
	ClientIP      string
	RequestID     string
	CreatedAt     time.Time
	ExpiresAt     time.Time
	VerifiedAt    *time.Time
	Reason        string
	Attempts      int
	LastRetryAt   time.Time
}

// IsSMS reports whether the verification accepts a code by SMS.
func (v *Verification) IsSMS() bool {
	return v.Method == MethodSMS || v.Method == MethodAny
}

// IsCall reports whether the verification accepts a call.
func (v *Verification) IsCall() bool {
	return v.Method == MethodCall || v.Method == MethodAny
}

// Gateway is a phone running the PhoneGate application.
type Gateway struct {
	ID         string
	TokenHash  string
	Name       string
	Pool       string
	Status     string
	AppVersion string
	LastSeenAt time.Time
	CreatedAt  time.Time
}

// GatewayNumber is a SIM number reported by a gateway.
type GatewayNumber struct {
	E164           string
	GatewayID      string
	Pool           string
	Status         string
	FirstSeenAt    time.Time
	LastSeenAt     time.Time
	LastAssignedAt time.Time
}

// InboundEvent is a received SMS or call.
type InboundEvent struct {
	ID            string
	GatewayID     string
	GatewayNumber string
	Kind          string
	Direction     string
	PeerPhone     string
	EventTime     time.Time
	ReceivedAt    time.Time
	Text          string
	Duration      int
	DedupeKey     string
	SessionID     string
}

// SessionFilter selects verifications for the list endpoint.
type SessionFilter struct {
	TenantID string
	Mode     string
	Status   string
	Phone    string
	From     time.Time
	To       time.Time
	Limit    int
	Cursor   string
}
