package store

import (
	"bytes"
	"context"
	"encoding/base64"
	"fmt"
	"sort"
	"strings"
	"sync"
	"time"

	"phonegate/backend/internal/model"
)

// Memory is a process-local store. It is safe for concurrent use and enforces
// the two important uniqueness rules of the domain: SMS codes are unique per
// gateway number, and call/any sessions are unique per (phone, gateway).
type Memory struct {
	mu sync.RWMutex

	gateways   map[string]*model.Gateway
	byToken    map[string]string
	numbers    map[string]*model.GatewayNumber
	sessions   map[string]*model.Verification
	events     map[string]*model.InboundEvent
	eventOrder []string
}

func NewMemory() *Memory {
	return &Memory{
		gateways: make(map[string]*model.Gateway),
		byToken:  make(map[string]string),
		numbers:  make(map[string]*model.GatewayNumber),
		sessions: make(map[string]*model.Verification),
		events:   make(map[string]*model.InboundEvent),
	}
}

func (m *Memory) Ping(context.Context) error { return nil }
func (m *Memory) Close() error               { return nil }

func (m *Memory) UpsertGateway(_ context.Context, gw model.Gateway) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if old, ok := m.gateways[gw.ID]; ok && old.TokenHash != "" {
		delete(m.byToken, old.TokenHash)
	}
	copy := gw
	if copy.CreatedAt.IsZero() {
		copy.CreatedAt = time.Now().UTC()
	}
	m.gateways[gw.ID] = &copy
	if gw.TokenHash != "" {
		m.byToken[gw.TokenHash] = gw.ID
	}
	return nil
}

func (m *Memory) GatewayByTokenHash(_ context.Context, hash string) (*model.Gateway, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	id, ok := m.byToken[hash]
	if !ok {
		return nil, ErrNotFound
	}
	gw, ok := m.gateways[id]
	if !ok {
		return nil, ErrNotFound
	}
	return cloneGateway(gw), nil
}

func (m *Memory) GatewayByID(_ context.Context, id string) (*model.Gateway, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	gw, ok := m.gateways[id]
	if !ok {
		return nil, ErrNotFound
	}
	return cloneGateway(gw), nil
}

func (m *Memory) TouchGateway(_ context.Context, id string, at time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	gw, ok := m.gateways[id]
	if !ok {
		return ErrNotFound
	}
	gw.LastSeenAt = at
	if gw.Status != model.GatewayDisabled {
		gw.Status = model.GatewayActive
	}
	return nil
}

func (m *Memory) PreloadNumbers(_ context.Context, numbers []model.GatewayNumber) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, n := range numbers {
		if _, ok := m.numbers[n.E164]; ok {
			continue
		}
		copy := n
		if copy.FirstSeenAt.IsZero() {
			copy.FirstSeenAt = time.Now().UTC()
		}
		m.numbers[n.E164] = &copy
	}
	return nil
}

func (m *Memory) RegisterNumber(_ context.Context, number model.GatewayNumber) (*model.GatewayNumber, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	existing, ok := m.numbers[number.E164]
	if !ok {
		copy := number
		if copy.FirstSeenAt.IsZero() {
			copy.FirstSeenAt = time.Now().UTC()
		}
		if copy.Status == "" {
			copy.Status = model.NumberPending
		}
		m.numbers[number.E164] = &copy
		return cloneNumber(&copy), nil
	}
	if existing.GatewayID != "" && number.GatewayID != "" && existing.GatewayID != number.GatewayID {
		return nil, ErrNumberOwned
	}
	if existing.GatewayID == "" {
		existing.GatewayID = number.GatewayID
		existing.Pool = number.Pool
	}
	if number.LastSeenAt.After(existing.LastSeenAt) {
		existing.LastSeenAt = number.LastSeenAt
	}
	if existing.Status != model.NumberDisabled {
		if existing.Status == "" || existing.Status == model.NumberPending {
			existing.Status = number.Status
		}
		if number.Status == model.NumberActive && existing.Status != model.NumberDisabled {
			existing.Status = model.NumberActive
		}
	}
	if existing.Pool == "" {
		existing.Pool = number.Pool
	}
	return cloneNumber(existing), nil
}

func (m *Memory) Number(_ context.Context, e164 string) (*model.GatewayNumber, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	n, ok := m.numbers[e164]
	if !ok {
		return nil, ErrNotFound
	}
	return cloneNumber(n), nil
}

func (m *Memory) NumbersByPools(_ context.Context, pools []string) ([]model.GatewayNumber, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	want := make(map[string]struct{}, len(pools))
	for _, p := range pools {
		want[p] = struct{}{}
	}
	out := make([]model.GatewayNumber, 0)
	for _, n := range m.numbers {
		if _, ok := want[n.Pool]; !ok {
			continue
		}
		out = append(out, *cloneNumber(n))
	}
	sort.Slice(out, func(i, j int) bool { return out[i].E164 < out[j].E164 })
	return out, nil
}

func (m *Memory) TouchNumber(_ context.Context, e164 string, at time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	n, ok := m.numbers[e164]
	if !ok {
		return ErrNotFound
	}
	if at.After(n.LastSeenAt) {
		n.LastSeenAt = at
	}
	return nil
}

func (m *Memory) SetNumberAssigned(_ context.Context, e164 string, at time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	n, ok := m.numbers[e164]
	if !ok {
		return ErrNotFound
	}
	n.LastAssignedAt = at
	return nil
}

func (m *Memory) CreateSession(_ context.Context, v *model.Verification) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, exists := m.sessions[v.ID]; exists {
		return fmt.Errorf("session %s already exists", v.ID)
	}
	for _, other := range m.sessions {
		if other.Status != model.StatusPending {
			continue
		}
		if other.GatewayNumber != v.GatewayNumber {
			continue
		}
		if v.CodeHash != nil && other.CodeHash != nil && bytes.Equal(v.CodeHash, other.CodeHash) {
			return ErrCodeConflict
		}
		if v.Phone != nil && v.IsCall() && other.Phone != nil && other.IsCall() &&
			other.Phone != nil && *other.Phone == *v.Phone {
			return ErrCallConflict
		}
	}
	m.sessions[v.ID] = cloneVerification(v)
	return nil
}

func (m *Memory) UpdateSession(_ context.Context, v *model.Verification) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.sessions[v.ID]; !ok {
		return ErrNotFound
	}
	m.sessions[v.ID] = cloneVerification(v)
	return nil
}

func (m *Memory) Session(_ context.Context, tenantID, id string) (*model.Verification, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	v, ok := m.sessions[id]
	if !ok || v.TenantID != tenantID {
		return nil, ErrNotFound
	}
	return cloneVerification(v), nil
}

func (m *Memory) SessionByID(_ context.Context, id string) (*model.Verification, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	v, ok := m.sessions[id]
	if !ok {
		return nil, ErrNotFound
	}
	return cloneVerification(v), nil
}

func (m *Memory) PendingSessionsByGateway(_ context.Context, gatewayNumber string) ([]*model.Verification, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	out := make([]*model.Verification, 0)
	for _, v := range m.sessions {
		if v.Status == model.StatusPending && v.GatewayNumber == gatewayNumber {
			out = append(out, cloneVerification(v))
		}
	}
	return out, nil
}

func (m *Memory) PendingSessionsByPhone(_ context.Context, tenantID, phone string) ([]*model.Verification, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	out := make([]*model.Verification, 0)
	for _, v := range m.sessions {
		if v.Status == model.StatusPending && v.TenantID == tenantID && v.Phone != nil && *v.Phone == phone {
			out = append(out, cloneVerification(v))
		}
	}
	return out, nil
}

func (m *Memory) ListSessions(_ context.Context, filter model.SessionFilter) ([]*model.Verification, int, error) {
	m.mu.RLock()
	out := make([]*model.Verification, 0)
	for _, v := range m.sessions {
		if v.TenantID != filter.TenantID {
			continue
		}
		if filter.Mode != "" && v.Mode != filter.Mode {
			continue
		}
		if filter.Status != "" && v.Status != filter.Status {
			continue
		}
		if filter.Phone != "" && (v.Phone == nil || *v.Phone != filter.Phone) {
			continue
		}
		if !filter.From.IsZero() && v.CreatedAt.Before(filter.From) {
			continue
		}
		if !filter.To.IsZero() && v.CreatedAt.After(filter.To) {
			continue
		}
		out = append(out, cloneVerification(v))
	}
	m.mu.RUnlock()

	sort.Slice(out, func(i, j int) bool {
		if out[i].CreatedAt.Equal(out[j].CreatedAt) {
			return out[i].ID > out[j].ID
		}
		return out[i].CreatedAt.After(out[j].CreatedAt)
	})
	total := len(out)
	if filter.Cursor != "" {
		at, id, err := decodeCursor(filter.Cursor)
		if err != nil {
			return nil, 0, err
		}
		idx := 0
		for idx < len(out) {
			if out[idx].CreatedAt.Equal(at) && out[idx].ID == id {
				idx++
				break
			}
			idx++
		}
		out = out[idx:]
	}
	limit := filter.Limit
	if limit <= 0 {
		limit = 50
	}
	if limit > 200 {
		limit = 200
	}
	if len(out) > limit {
		out = out[:limit]
	}
	return out, total, nil
}

func (m *Memory) CountPendingByNumber(_ context.Context, gatewayNumber string) (int, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	n := 0
	for _, v := range m.sessions {
		if v.Status == model.StatusPending && v.GatewayNumber == gatewayNumber {
			n++
		}
	}
	return n, nil
}

func (m *Memory) CountActiveSMSByPhone(_ context.Context, tenantID, phone string) (int, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	n := 0
	for _, v := range m.sessions {
		if v.Status == model.StatusPending && v.TenantID == tenantID && v.Phone != nil &&
			*v.Phone == phone && v.IsSMS() {
			n++
		}
	}
	return n, nil
}

func (m *Memory) CountPendingCodeFirst(_ context.Context, tenantID string) (int, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	n := 0
	for _, v := range m.sessions {
		if v.Status == model.StatusPending && v.TenantID == tenantID && v.Mode == model.ModeCodeFirst {
			n++
		}
	}
	return n, nil
}

func (m *Memory) Expire(_ context.Context, now time.Time) ([]*model.Verification, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	var out []*model.Verification
	for _, v := range m.sessions {
		if v.Status == model.StatusPending && !v.ExpiresAt.After(now) {
			v.Status = model.StatusExpired
			out = append(out, cloneVerification(v))
		}
	}
	return out, nil
}

func (m *Memory) AddEvent(_ context.Context, event *model.InboundEvent) (bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.events[event.DedupeKey]; ok {
		return false, nil
	}
	copy := *event
	m.events[event.DedupeKey] = &copy
	m.eventOrder = append(m.eventOrder, event.DedupeKey)
	return true, nil
}

// Events returns a snapshot of stored inbound events in arrival order. It is
// used by tests and diagnostics; the Store interface deliberately exposes only
// the write path of the journal.
func (m *Memory) Events() []*model.InboundEvent {
	m.mu.RLock()
	defer m.mu.RUnlock()
	out := make([]*model.InboundEvent, 0, len(m.eventOrder))
	for _, key := range m.eventOrder {
		if e, ok := m.events[key]; ok {
			copy := *e
			out = append(out, &copy)
		}
	}
	return out
}

func (m *Memory) AttachEvent(_ context.Context, dedupeKey, sessionID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	e, ok := m.events[dedupeKey]
	if !ok {
		return ErrNotFound
	}
	e.SessionID = sessionID
	return nil
}

func cloneGateway(g *model.Gateway) *model.Gateway {
	if g == nil {
		return nil
	}
	c := *g
	return &c
}

func cloneNumber(n *model.GatewayNumber) *model.GatewayNumber {
	if n == nil {
		return nil
	}
	c := *n
	return &c
}

func cloneVerification(v *model.Verification) *model.Verification {
	if v == nil {
		return nil
	}
	c := *v
	if v.Phone != nil {
		p := *v.Phone
		c.Phone = &p
	}
	if v.VerifiedAt != nil {
		t := *v.VerifiedAt
		c.VerifiedAt = &t
	}
	if v.CodeHash != nil {
		c.CodeHash = append([]byte(nil), v.CodeHash...)
	}
	if v.Metadata != nil {
		c.Metadata = make(map[string]any, len(v.Metadata))
		for k, val := range v.Metadata {
			c.Metadata[k] = val
		}
	}
	return &c
}

func encodeCursor(t time.Time, id string) string {
	return base64.RawURLEncoding.EncodeToString([]byte(t.UTC().Format(time.RFC3339Nano) + "|" + id))
}

func decodeCursor(cursor string) (time.Time, string, error) {
	raw, err := base64.RawURLEncoding.DecodeString(cursor)
	if err != nil {
		return time.Time{}, "", fmt.Errorf("invalid cursor")
	}
	parts := strings.SplitN(string(raw), "|", 2)
	if len(parts) != 2 {
		return time.Time{}, "", fmt.Errorf("invalid cursor")
	}
	t, err := time.Parse(time.RFC3339Nano, parts[0])
	if err != nil {
		return time.Time{}, "", fmt.Errorf("invalid cursor")
	}
	return t, parts[1], nil
}

// CursorFor builds the opaque cursor for the next page.
func CursorFor(v *model.Verification) string {
	return encodeCursor(v.CreatedAt, v.ID)
}
