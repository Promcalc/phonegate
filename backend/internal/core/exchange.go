package core

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
	"time"

	"phonegate/backend/internal/code"
	"phonegate/backend/internal/model"
	"phonegate/backend/internal/phone"
	"phonegate/backend/internal/store"
)

// HandleExchange accepts one synchronization window from a gateway. Invalid
// individual records are logged and skipped; the device's cursor must advance
// so a bad row cannot block the queue forever.
func (s *Service) HandleExchange(ctx context.Context, gateway *model.Gateway, req *ExchangeRequest) error {
	if gateway == nil {
		return errUnauthorized("Шлюз не найден")
	}
	if req == nil || strings.TrimSpace(req.SyncID) == "" {
		return errBad("invalid_request", "Требуется sync_id")
	}
	now := s.now().UTC()

	s.mu.Lock()
	defer s.mu.Unlock()

	if err := s.store.TouchGateway(ctx, gateway.ID, now); err != nil {
		return errInternal("Ошибка хранилища")
	}
	if len(req.Sims) > 4 {
		req.Sims = req.Sims[:4]
	}
	for _, sim := range req.Sims {
		e164, err := phone.Normalize(sim.Phone)
		if err != nil {
			s.log.Warn("gateway.invalid_number", "gateway", gateway.ID, "raw_number", truncateLog(sim.Phone, 64))
			continue
		}
		number, err := s.registerNumber(ctx, gateway, e164, now)
		if err != nil {
			if errors.Is(err, store.ErrNumberOwned) {
				s.log.Warn("gateway.number_owned", "gateway", gateway.ID, "number", phone.Mask(e164))
			} else {
				s.log.Error("gateway.number_register_failed", "gateway", gateway.ID, "number", phone.Mask(e164), "error", err)
			}
			continue
		}
		_ = s.store.TouchNumber(ctx, number.E164, now)
		for _, item := range sim.SMS {
			s.handleSMS(ctx, gateway, number, item, now)
		}
		for _, item := range sim.Call {
			s.handleCall(ctx, gateway, number, item, now)
		}
	}
	return nil
}

func (s *Service) registerNumber(ctx context.Context, gateway *model.Gateway, e164 string, now time.Time) (*model.GatewayNumber, error) {
	existing, err := s.store.Number(ctx, e164)
	if err != nil && !errors.Is(err, store.ErrNotFound) {
		return nil, err
	}
	if existing == nil {
		if !s.cfg.Gateway.AutoRegisterNumbers && !s.cfg.IsConfiguredPoolNumber(e164) {
			return nil, fmt.Errorf("auto-registration disabled")
		}
	}
	status := model.NumberPending
	if s.cfg.IsConfiguredPoolNumber(e164) {
		status = model.NumberActive
	} else if !s.cfg.Gateway.RequireApproval {
		status = model.NumberActive
	}
	pool := gateway.Pool
	if pool == "" {
		status = model.NumberPending
	}
	n := model.GatewayNumber{
		E164:        e164,
		GatewayID:   gateway.ID,
		Pool:        pool,
		Status:      status,
		FirstSeenAt: now,
		LastSeenAt:  now,
	}
	registered, err := s.store.RegisterNumber(ctx, n)
	if err != nil {
		return nil, err
	}
	if existing == nil {
		s.log.Info("gateway.number_registered", "gateway", gateway.ID, "number", phone.Mask(e164), "pool", pool, "status", registered.Status)
	}
	return registered, nil
}

func (s *Service) handleSMS(ctx context.Context, gateway *model.Gateway, number *model.GatewayNumber, item SMSItem, now time.Time) {
	eventTime, err := time.Parse(time.RFC3339, strings.TrimSpace(item.Time))
	if err != nil {
		s.log.Warn("event.invalid_time", "gateway", gateway.ID, "kind", "sms", "raw_time", truncateLog(item.Time, 40))
		return
	}
	eventTime = eventTime.UTC()
	if len(item.Text) > 4096 {
		s.log.Warn("event.text_too_long", "gateway", gateway.ID, "text_len", len([]rune(item.Text)))
		return
	}
	peer, err := phone.Normalize(item.Phone)
	if err != nil {
		rawPeer := truncateLog(item.Phone, 64)
		if s.cfg.Gateway.ValidatePeerNumbers {
			s.log.Warn("event.invalid_peer",
				"gateway", gateway.ID,
				"kind", "sms",
				"raw_phone", rawPeer,
				"raw_time", truncateLog(item.Time, 40),
				"text_len", len([]rune(item.Text)),
			)
			return
		}
		// The operator explicitly disabled peer validation: keep the event in
		// the journal for diagnostics, but never match it with a verification.
		s.log.Warn("event.invalid_peer_accepted",
			"gateway", gateway.ID,
			"kind", "sms",
			"raw_phone", rawPeer,
			"raw_time", truncateLog(item.Time, 40),
			"text_len", len([]rune(item.Text)),
		)
		if rawPeer == "" {
			return
		}
		ev := s.event(gateway, number, model.EventSMS, "", rawPeer, eventTime, item.Text, 0, now)
		if _, err := s.store.AddEvent(ctx, ev); err != nil {
			s.log.Error("event.store_failed", "gateway", gateway.ID, "error", err)
		}
		return
	}
	ev := s.event(gateway, number, model.EventSMS, "", peer, eventTime, item.Text, 0, now)
	added, err := s.store.AddEvent(ctx, ev)
	if err != nil {
		s.log.Error("event.store_failed", "gateway", gateway.ID, "error", err)
		return
	}
	if !added {
		return
	}
	if !s.withinClockSkew(eventTime, now) {
		s.log.Info("event.outside_window",
			"gateway", gateway.ID,
			"kind", "sms",
			"event_time", eventTime.Format(time.RFC3339),
			"server_time", now.Format(time.RFC3339),
		)
		return
	}
	tenantID, ok := s.cfg.PoolTenant()[number.Pool]
	if !ok {
		return
	}
	pending, _ := s.store.PendingSessionsByGateway(ctx, number.E164)
	if len(pending) == 0 {
		return
	}
	candidates := smsCandidates(item.Text)
	if len(candidates) == 0 {
		s.log.Debug("event.unmatched", "gateway", gateway.ID, "kind", "sms", "reason", "no_code")
		return
	}
	matches := make(map[string]*model.Verification)
	for _, v := range pending {
		if v.TenantID != tenantID || v.CodeHash == nil {
			continue
		}
		for _, candidate := range candidates {
			if code.EqualHash(v.CodeHash, candidate) {
				matches[v.ID] = v
				break
			}
		}
	}
	switch len(matches) {
	case 0:
		s.log.Debug("event.unmatched", "gateway", gateway.ID, "kind", "sms")
		return
	case 1:
		for _, v := range matches {
			s.acceptSMS(ctx, ev, v, peer, now)
		}
	default:
		s.log.Warn("event.ambiguous", "gateway", gateway.ID, "kind", "sms", "matches", len(matches))
	}
}

func (s *Service) acceptSMS(ctx context.Context, ev *model.InboundEvent, v *model.Verification, peer string, now time.Time) {
	if v.Phone != nil && *v.Phone != peer {
		v.Attempts++
		tenant, _ := s.cfg.Tenant(v.TenantID)
		maxAttempts := tenant.Verification.MaxAttempts
		if maxAttempts <= 0 {
			maxAttempts = s.cfg.Verification.MaxAttempts
		}
		if v.Attempts >= maxAttempts {
			v.Status = model.StatusFailed
			v.Reason = model.ReasonTooManyAttempts
		}
		_ = s.store.UpdateSession(ctx, v)
		s.log.Warn("verification.sender_mismatch", "tenant", v.TenantID, "verification_id", v.ID, "attempts", v.Attempts)
		return
	}
	if v.Mode == model.ModeCodeFirst {
		tenant, _ := s.cfg.Tenant(v.TenantID)
		if len(tenant.AllowedCountries) > 0 {
			country := phone.CountryCode(peer)
			allowed := false
			for _, candidate := range tenant.AllowedCountries {
				if strings.EqualFold(candidate, country) {
					allowed = true
					break
				}
			}
			if !allowed {
				s.log.Warn("verification.country_rejected", "tenant", v.TenantID, "verification_id", v.ID, "country", country)
				return
			}
		}
		p := peer
		v.Phone = &p
	}
	s.verify(ctx, ev, v, now, "sms")
}

func (s *Service) handleCall(ctx context.Context, gateway *model.Gateway, number *model.GatewayNumber, item CallItem, now time.Time) {
	eventTime, err := time.Parse(time.RFC3339, strings.TrimSpace(item.Time))
	if err != nil {
		s.log.Warn("event.invalid_time", "gateway", gateway.ID, "kind", "call", "raw_time", truncateLog(item.Time, 40))
		return
	}
	eventTime = eventTime.UTC()
	direction := strings.ToLower(strings.TrimSpace(item.Direction))
	if direction == "" {
		direction = model.DirectionIn
	}
	if direction != model.DirectionIn && direction != model.DirectionOut && direction != model.DirectionMissed {
		s.log.Warn("event.invalid_direction", "gateway", gateway.ID, "direction", truncateLog(item.Direction, 16))
		return
	}
	duration := item.Duration
	if duration < 0 {
		duration = 0
	}
	peer, err := phone.Normalize(item.Phone)
	if err != nil {
		rawPeer := truncateLog(item.Phone, 64)
		if s.cfg.Gateway.ValidatePeerNumbers {
			s.log.Warn("event.invalid_peer",
				"gateway", gateway.ID,
				"kind", "call",
				"raw_phone", rawPeer,
				"raw_time", truncateLog(item.Time, 40),
				"direction", direction,
			)
			return
		}
		// Keep invalid peers in the journal when validation is disabled, but
		// do not try to match them: verifications always store E.164.
		s.log.Warn("event.invalid_peer_accepted",
			"gateway", gateway.ID,
			"kind", "call",
			"raw_phone", rawPeer,
			"raw_time", truncateLog(item.Time, 40),
			"direction", direction,
		)
		if rawPeer == "" {
			return
		}
		ev := s.event(gateway, number, model.EventCall, direction, rawPeer, eventTime, "", duration, now)
		if _, err := s.store.AddEvent(ctx, ev); err != nil {
			s.log.Error("event.store_failed", "gateway", gateway.ID, "error", err)
		}
		return
	}
	ev := s.event(gateway, number, model.EventCall, direction, peer, eventTime, "", duration, now)
	added, err := s.store.AddEvent(ctx, ev)
	if err != nil {
		s.log.Error("event.store_failed", "gateway", gateway.ID, "error", err)
		return
	}
	if !added || direction == model.DirectionOut {
		return
	}
	if !s.withinClockSkew(eventTime, now) {
		s.log.Info("event.outside_window",
			"gateway", gateway.ID,
			"kind", "call",
			"event_time", eventTime.Format(time.RFC3339),
			"server_time", now.Format(time.RFC3339),
		)
		return
	}
	isMissed := direction == model.DirectionMissed || (direction == model.DirectionIn && duration == 0)
	if isMissed && !s.cfg.Verification.Call.AllowMissed {
		return
	}
	if !isMissed && !s.cfg.Verification.Call.AllowAnswered {
		return
	}
	if !isMissed && duration < s.cfg.Verification.Call.MinDurationSeconds {
		return
	}
	tenantID, ok := s.cfg.PoolTenant()[number.Pool]
	if !ok {
		return
	}
	pending, _ := s.store.PendingSessionsByGateway(ctx, number.E164)
	var matches []*model.Verification
	for _, v := range pending {
		if v.TenantID == tenantID && v.IsCall() && v.Phone != nil && *v.Phone == peer {
			matches = append(matches, v)
		}
	}
	switch len(matches) {
	case 0:
		s.log.Debug("event.unmatched", "gateway", gateway.ID, "kind", "call")
	case 1:
		s.verify(ctx, ev, matches[0], now, "call")
	default:
		s.log.Warn("event.ambiguous", "gateway", gateway.ID, "kind", "call", "matches", len(matches))
	}
}

func (s *Service) verify(ctx context.Context, ev *model.InboundEvent, v *model.Verification, now time.Time, kind string) {
	t := now.UTC()
	v.Status = model.StatusVerified
	v.VerifiedAt = &t
	if err := s.store.UpdateSession(ctx, v); err != nil {
		s.log.Error("verification.update_failed", "verification_id", v.ID, "error", err)
		return
	}
	_ = s.store.AttachEvent(ctx, ev.DedupeKey, v.ID)
	s.log.Info("verification.verified",
		"tenant", v.TenantID,
		"verification_id", v.ID,
		"event", kind,
		"latency_ms", now.Sub(v.CreatedAt).Milliseconds(),
	)
}

func (s *Service) event(gateway *model.Gateway, number *model.GatewayNumber, kind, direction, peer string, eventTime time.Time, text string, duration int, now time.Time) *model.InboundEvent {
	textHash := sha256.Sum256([]byte(text))
	raw := fmt.Sprintf("%s|%s|%s|%s|%s|%x|%d", gateway.ID, kind, peer, eventTime.Format(time.RFC3339Nano), direction, textHash, duration)
	sum := sha256.Sum256([]byte(raw))
	return &model.InboundEvent{
		ID:            NewID("evt_"),
		GatewayID:     gateway.ID,
		GatewayNumber: number.E164,
		Kind:          kind,
		Direction:     direction,
		PeerPhone:     peer,
		EventTime:     eventTime,
		ReceivedAt:    now,
		Text:          text,
		Duration:      duration,
		DedupeKey:     hex.EncodeToString(sum[:]),
	}
}

func (s *Service) withinClockSkew(eventTime, now time.Time) bool {
	skew := s.cfg.Verification.ClockSkew.Std()
	if skew <= 0 {
		skew = 2 * time.Minute
	}
	delta := now.Sub(eventTime)
	if delta < 0 {
		delta = -delta
	}
	return delta <= skew
}

// truncateLog keeps diagnostic log values readable and bounded. It is used
// only for untrusted gateway input (raw phone numbers, times, directions).
func truncateLog(value string, max int) string {
	value = strings.TrimSpace(value)
	if max <= 0 {
		return value
	}
	runes := []rune(value)
	if len(runes) <= max {
		return value
	}
	return string(runes[:max]) + "…"
}

func smsCandidates(text string) []string {
	tokens := code.Candidates(text, 0)
	if len(tokens) == 0 {
		return nil
	}
	seen := make(map[string]struct{}, len(tokens))
	out := make([]string, 0, len(tokens)*2)
	add := func(value string) {
		if value == "" {
			return
		}
		value = code.Normalize(value)
		if _, ok := seen[value]; ok {
			return
		}
		seen[value] = struct{}{}
		out = append(out, value)
	}
	for _, t := range tokens {
		add(t)
	}
	// Word-style code-first codes are joined by "-". Add contiguous
	// sequences so a message containing "ALFA-BRAVO-..." still matches.
	for n := 2; n <= 6; n++ {
		for i := 0; i+n <= len(tokens); i++ {
			add(strings.Join(tokens[i:i+n], "-"))
		}
	}
	return out
}
