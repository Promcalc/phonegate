package core

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"log/slog"
	"math/big"
	"net/url"
	"sort"
	"strings"
	"sync"
	"time"

	"phonegate/backend/internal/code"
	"phonegate/backend/internal/config"
	"phonegate/backend/internal/links"
	"phonegate/backend/internal/model"
	"phonegate/backend/internal/phone"
	"phonegate/backend/internal/ratelimit"
	"phonegate/backend/internal/store"
)

const pollAfterMS = 3000

var purposeAllowed = map[string]bool{
	"login": true, "signup": true, "confirm": true, "recovery": true, "telegram_bind": true,
}

var codeWords = []string{
	"ALFA", "BRAVO", "CHARLIE", "DELTA", "ECHO", "FOXTROT", "GOLF", "HOTEL",
	"INDIA", "JULIET", "KILO", "LIMA", "MIKE", "NOVEMBER", "OSCAR", "PAPA",
	"QUEBEC", "ROMEO", "SIERRA", "TANGO", "UNIFORM", "VICTOR", "WHISKEY", "XRAY",
	"YANKEE", "ZULU", "AMBER", "BROWN", "CORAL", "GREEN", "IVORY", "SILVER",
}

type createSpec struct {
	TenantID    string
	Tenant      config.TenantConfig
	Mode        string
	Method      string
	Phone       *string
	Purpose     string
	Locale      string
	TTL         time.Duration
	Metadata    map[string]any
	CallbackURL string
	CodeLength  int
	CodeFormat  string
	CodeWords   int
}

type idemEntry struct {
	BodyHash string
	Created  *Created
	Expires  time.Time
}

type Service struct {
	cfg   *config.Config
	store store.Store
	log   *slog.Logger
	links *links.Manager
	now   func() time.Time

	mu   sync.Mutex
	idem map[string]idemEntry
	rr   map[string]int

	incomingCodes *ratelimit.Limiter
}

func NewService(cfg *config.Config, st store.Store, log *slog.Logger, lm *links.Manager, incomingCodes *ratelimit.Limiter) *Service {
	if log == nil {
		log = slog.Default()
	}
	return &Service{
		cfg:           cfg,
		store:         st,
		log:           log,
		links:         lm,
		now:           func() time.Time { return time.Now().UTC() },
		idem:          make(map[string]idemEntry),
		rr:            make(map[string]int),
		incomingCodes: incomingCodes,
	}
}

// SetClock is used by tests.
func (s *Service) SetClock(fn func() time.Time) { s.now = fn }

func (s *Service) Now() time.Time { return s.now().UTC() }

func (s *Service) Store() store.Store { return s.store }

func (s *Service) Config() *config.Config { return s.cfg }

func (s *Service) Links() *links.Manager { return s.links }

// GetByID reads a session without checking tenant ownership. It is used only
// by the public, token-protected page.
func (s *Service) GetByID(ctx context.Context, id string) (*model.Verification, error) {
	v, err := s.store.SessionByID(ctx, id)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			return nil, errNotFound()
		}
		return nil, errInternal("Ошибка хранилища")
	}
	return v, nil
}

func (s *Service) TenantInfo(tenantID string) (TenantInfo, bool) {
	tenant, ok := s.cfg.Tenant(tenantID)
	if !ok {
		return TenantInfo{}, false
	}
	methods := append([]string(nil), tenant.Verification.Methods...)
	modes := []string{model.ModePhoneFirst}
	if s.cfg.Verification.CodeFirst.Enabled {
		modes = append(modes, model.ModeCodeFirst)
	}
	return TenantInfo{
		ID:      tenantID,
		Name:    tenant.Name,
		Enabled: tenant.Enabled,
		Pools:   append([]string(nil), tenant.Pools...),
		Methods: methods,
		Modes:   modes,
		Limits: map[string]any{
			"per_phone":  tenant.RateLimits.PerPhone,
			"per_ip":     tenant.RateLimits.PerIP,
			"per_tenant": tenant.RateLimits.PerTenant,
		},
	}, true
}

// Create creates a verification, enforcing idempotency and the call/SMS
// concurrency rules.
func (s *Service) Create(ctx context.Context, tenantID string, req CreateRequest, clientIP, requestID, idemKey string, replace bool) (*Created, error) {
	now := s.now().UTC()
	bodyHash := hashCreateRequest(req)
	if len(idemKey) > 64 {
		return nil, errBad("invalid_request", "Idempotency-Key слишком длинный")
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	s.expireLocked(ctx, now)
	if idemKey != "" {
		key := tenantID + "\x00" + idemKey
		if entry, ok := s.idem[key]; ok {
			if now.Before(entry.Expires) {
				if entry.BodyHash != bodyHash {
					return nil, errConflict("idempotency_conflict", "Idempotency-Key уже использован с другим телом")
				}
				return cloneCreated(entry.Created), nil
			}
			delete(s.idem, key)
		}
	}

	spec, err := s.specFor(tenantID, req)
	if err != nil {
		return nil, err
	}
	created, err := s.createLocked(ctx, spec, clientIP, requestID, now, replace)
	if err != nil {
		return nil, err
	}
	if idemKey != "" {
		ttl := s.cfg.Security.IdempotencyTTL.Std()
		if ttl <= 0 {
			ttl = 24 * time.Hour
		}
		s.idem[tenantID+"\x00"+idemKey] = idemEntry{
			BodyHash: bodyHash,
			Created:  cloneCreated(created),
			Expires:  now.Add(ttl),
		}
	}
	return created, nil
}

func hashCreateRequest(req CreateRequest) string {
	raw, _ := json.Marshal(req)
	sum := sha256.Sum256(raw)
	return hex.EncodeToString(sum[:])
}

func (s *Service) specFor(tenantID string, req CreateRequest) (*createSpec, error) {
	tenant, ok := s.cfg.Tenant(tenantID)
	if !ok {
		return nil, errUnauthorized("Арендатор не найден")
	}
	mode := strings.TrimSpace(req.Mode)
	if mode == "" {
		mode = model.ModePhoneFirst
	}
	if mode != model.ModePhoneFirst && mode != model.ModeCodeFirst {
		return nil, errBad("invalid_request", "Неизвестный mode")
	}
	method := strings.TrimSpace(req.Method)
	if method == "" {
		method = tenant.Verification.DefaultMethod
	}
	if method == "" {
		method = model.MethodSMS
	}
	if method != model.MethodSMS && method != model.MethodCall && method != model.MethodAny {
		return nil, errBad("invalid_request", "Неизвестный method")
	}
	if !contains(tenant.Verification.Methods, method) {
		return nil, errBad("invalid_request", "Метод не разрешён арендатору")
	}

	var normalizedPhone *string
	if mode == model.ModeCodeFirst {
		if strings.TrimSpace(req.Phone) != "" {
			return nil, errBad("phone_not_allowed", "В режиме code_first номер не передаётся")
		}
		if method != model.MethodSMS {
			return nil, errBad("code_first_sms_only", "В режиме code_first доступен только SMS")
		}
		if !s.cfg.Verification.CodeFirst.Enabled {
			return nil, errBad("invalid_request", "Режим code_first выключен")
		}
	} else {
		if strings.TrimSpace(req.Phone) == "" {
			return nil, errBad("phone_required", "Требуется номер телефона")
		}
		p, err := phone.Normalize(req.Phone)
		if err != nil {
			return nil, errBad("invalid_phone", "Некорректный номер телефона")
		}
		if len(tenant.AllowedCountries) > 0 {
			country := phone.CountryCode(p)
			ok := false
			for _, c := range tenant.AllowedCountries {
				if strings.EqualFold(c, country) {
					ok = true
					break
				}
			}
			if !ok {
				return nil, errBad("invalid_phone", "Страна номера не разрешена")
			}
		}
		normalizedPhone = &p
	}

	purpose := strings.TrimSpace(req.Purpose)
	if purpose == "" {
		purpose = "login"
	}
	if !purposeAllowed[purpose] {
		return nil, errBad("invalid_request", "Неизвестное purpose")
	}
	locale := strings.TrimSpace(req.Locale)
	if locale == "" {
		locale = "ru"
	}
	if locale != "ru" && locale != "en" {
		return nil, errBad("invalid_request", "locale должен быть ru или en")
	}

	ttl := tenant.Verification.TTL.Std()
	if mode == model.ModeCodeFirst && req.TTLSeconds == 0 {
		ttl = s.cfg.Verification.CodeFirst.TTL.Std()
	}
	if req.TTLSeconds != 0 {
		if req.TTLSeconds < 60 || req.TTLSeconds > int(s.cfg.Verification.MaxTTL.Std().Seconds()) {
			return nil, errBad("invalid_request", "ttl_seconds вне допустимого диапазона")
		}
		ttl = time.Duration(req.TTLSeconds) * time.Second
	}
	if ttl < 60*time.Second || ttl > s.cfg.Verification.MaxTTL.Std() {
		return nil, errBad("invalid_request", "ttl_seconds вне допустимого диапазона")
	}

	if req.Metadata != nil {
		raw, err := json.Marshal(req.Metadata)
		if err != nil || len(raw) > 4096 {
			return nil, errBad("invalid_request", "metadata превышает 4 КиБ")
		}
	}

	callback := strings.TrimSpace(req.CallbackURL)
	if callback == "" {
		callback = tenant.Webhook.URL
	}
	if callback != "" {
		u, err := url.Parse(callback)
		if err != nil || u.Scheme == "" || u.Host == "" {
			return nil, errBad("invalid_request", "Некорректный callback_url")
		}
		allowed := false
		for _, candidate := range tenant.Webhook.AllowedCallbackURLs {
			if candidate == callback {
				allowed = true
				break
			}
		}
		// The tenant's configured webhook is always allowed.
		if tenant.Webhook.URL == callback {
			allowed = true
		}
		if !allowed {
			return nil, errBad("invalid_request", "callback_url не входит в allowlist")
		}
	}

	codeLength := tenant.Verification.CodeLength
	codeFormat := "digits"
	if mode == model.ModeCodeFirst {
		codeLength = s.cfg.Verification.CodeFirst.CodeLength
		codeFormat = s.cfg.Verification.CodeFirst.CodeFormat
	}

	return &createSpec{
		TenantID:    tenantID,
		Tenant:      tenant,
		Mode:        mode,
		Method:      method,
		Phone:       normalizedPhone,
		Purpose:     purpose,
		Locale:      locale,
		TTL:         ttl,
		Metadata:    req.Metadata,
		CallbackURL: callback,
		CodeLength:  codeLength,
		CodeFormat:  codeFormat,
		CodeWords:   s.cfg.Verification.CodeFirst.CodeWords,
	}, nil
}

func (s *Service) createLocked(ctx context.Context, spec *createSpec, clientIP, requestID string, now time.Time, replace bool) (*Created, error) {
	if spec.Phone != nil && spec.Method != model.MethodCall {
		count, _ := s.store.CountActiveSMSByPhone(ctx, spec.TenantID, *spec.Phone)
		if count >= s.cfg.Verification.MaxActiveSMSPerPhone {
			return nil, errRateLimited(time.Minute)
		}
	}
	if spec.Mode == model.ModeCodeFirst {
		count, _ := s.store.CountPendingCodeFirst(ctx, spec.TenantID)
		if count >= s.cfg.Verification.CodeFirst.MaxActivePerTenant {
			return nil, errRateLimited(time.Minute)
		}
	}
	if replace && spec.Phone != nil && spec.Method != model.MethodSMS {
		s.cancelCallSessionsLocked(ctx, spec.TenantID, *spec.Phone, now)
	}

	number, gateway, conflict, err := s.selectNumber(ctx, spec, now)
	if conflict != nil {
		return nil, &Error{
			Status:   409,
			Code:     "verification_already_pending",
			Message:  "У телефона уже есть активная call/any-заявка на этом номере шлюза",
			Existing: cloneVerification(conflict),
		}
	}
	if err != nil {
		return nil, err
	}
	if number == nil || gateway == nil {
		return nil, errNoGateway()
	}

	var created *Created
	for attempt := 0; attempt < 20; attempt++ {
		plainCode := ""
		var hash []byte
		if spec.Method != model.MethodCall {
			plainCode, hash, err = s.generateCode(spec)
			if err != nil {
				return nil, errInternal("Не удалось сгенерировать код")
			}
		}
		v := &model.Verification{
			ID:            NewID("vrf_"),
			TenantID:      spec.TenantID,
			Mode:          spec.Mode,
			Status:        model.StatusPending,
			Method:        spec.Method,
			Phone:         spec.Phone,
			GatewayID:     gateway.ID,
			GatewayNumber: number.E164,
			Pool:          number.Pool,
			CodeHash:      hash,
			Purpose:       spec.Purpose,
			Locale:        spec.Locale,
			Metadata:      spec.Metadata,
			CallbackURL:   spec.CallbackURL,
			ClientIP:      clientIP,
			RequestID:     requestID,
			CreatedAt:     now,
			ExpiresAt:     now.Add(spec.TTL),
		}
		if err := s.store.CreateSession(ctx, v); err != nil {
			switch {
			case errors.Is(err, store.ErrCodeConflict):
				continue
			case errors.Is(err, store.ErrCallConflict):
				existing := s.findCallConflict(ctx, spec.TenantID, spec.Phone, number.E164)
				return nil, &Error{
					Status:   409,
					Code:     "verification_already_pending",
					Message:  "У телефона уже есть активная call/any-заявка на этом номере шлюза",
					Existing: existing,
				}
			default:
				s.log.Error("verification.create_failed", "tenant", spec.TenantID, "error", err)
				return nil, errInternal("Не удалось создать заявку")
			}
		}
		_ = s.store.SetNumberAssigned(ctx, number.E164, now)
		linkSet := s.makeLinks(v, plainCode)
		created = &Created{
			Verification: v,
			Code:         plainCode,
			Links:        linkSet,
			Instructions: instructions(spec.Locale, spec.Method, plainCode, number.E164),
			PollAfterMS:  pollAfterMS,
		}
		break
	}
	if created == nil {
		return nil, errInternal("Не удалось подобрать уникальный код")
	}
	s.log.Info("verification.created",
		"tenant", spec.TenantID,
		"verification_id", created.Verification.ID,
		"mode", spec.Mode,
		"method", spec.Method,
		"phone", maskPtr(spec.Phone),
		"gateway_number", phone.Mask(number.E164),
		"ttl_s", int(spec.TTL.Seconds()),
	)
	return created, nil
}

func (s *Service) cancelCallSessionsLocked(ctx context.Context, tenantID, phoneNumber string, now time.Time) {
	sessions, err := s.store.PendingSessionsByPhone(ctx, tenantID, phoneNumber)
	if err != nil {
		return
	}
	for _, v := range sessions {
		if !v.IsCall() {
			continue
		}
		v.Status = model.StatusCancelled
		_ = s.store.UpdateSession(ctx, v)
		s.log.Info("verification.cancelled", "tenant", tenantID, "verification_id", v.ID, "reason", "replace", "at", now)
	}
}

func (s *Service) selectNumber(ctx context.Context, spec *createSpec, now time.Time) (*model.GatewayNumber, *config.GatewayItem, *model.Verification, error) {
	numbers, err := s.store.NumbersByPools(ctx, spec.Tenant.Pools)
	if err != nil {
		return nil, nil, nil, errInternal("Ошибка хранилища")
	}
	type candidate struct {
		number   model.GatewayNumber
		gateway  config.GatewayItem
		pending  int
		conflict *model.Verification
	}
	candidates := make([]candidate, 0)
	var anyConflict *model.Verification
	phoneSessions := []*model.Verification(nil)
	if spec.Phone != nil {
		phoneSessions, _ = s.store.PendingSessionsByPhone(ctx, spec.TenantID, *spec.Phone)
	}
	for _, n := range numbers {
		if n.Status != model.NumberActive || n.GatewayID == "" {
			continue
		}
		gw, ok := s.cfg.GatewayByID(n.GatewayID)
		if !ok || !gw.Enabled {
			continue
		}
		stored, err := s.store.GatewayByID(ctx, n.GatewayID)
		if err != nil || stored.Status == model.GatewayDisabled {
			continue
		}
		if stored.LastSeenAt.IsZero() || now.Sub(stored.LastSeenAt) > s.cfg.Gateway.OfflineAfter.Std() {
			continue
		}
		pending, _ := s.store.CountPendingByNumber(ctx, n.E164)
		if pending >= s.cfg.Verification.MaxPendingPerNumber {
			continue
		}
		var conflict *model.Verification
		if spec.Phone != nil && spec.Method != model.MethodSMS {
			for _, v := range phoneSessions {
				if v.IsCall() && v.GatewayNumber == n.E164 {
					conflict = v
					anyConflict = v
					break
				}
			}
			if conflict != nil {
				continue
			}
		}
		candidates = append(candidates, candidate{number: n, gateway: gw, pending: pending, conflict: conflict})
	}
	if len(candidates) == 0 {
		if anyConflict != nil {
			return nil, nil, anyConflict, nil
		}
		return nil, nil, nil, errNoGateway()
	}

	// Sticky: reuse the number of an existing session for this phone when it
	// is still a candidate.
	if s.cfg.Verification.Sticky && spec.Phone != nil {
		for _, v := range phoneSessions {
			for _, c := range candidates {
				if v.GatewayNumber == c.number.E164 {
					return &c.number, &c.gateway, nil, nil
				}
			}
		}
	}

	switch s.cfg.Verification.Selection {
	case "random":
		idx, err := rand.Int(rand.Reader, big.NewInt(int64(len(candidates))))
		if err == nil {
			c := candidates[idx.Int64()]
			return &c.number, &c.gateway, nil, nil
		}
	case "round_robin":
		key := spec.TenantID
		idx := s.rr[key] % len(candidates)
		s.rr[key]++
		c := candidates[idx]
		return &c.number, &c.gateway, nil, nil
	}

	sort.SliceStable(candidates, func(i, j int) bool {
		if candidates[i].pending != candidates[j].pending {
			return candidates[i].pending < candidates[j].pending
		}
		if !candidates[i].number.LastAssignedAt.Equal(candidates[j].number.LastAssignedAt) {
			return candidates[i].number.LastAssignedAt.Before(candidates[j].number.LastAssignedAt)
		}
		return candidates[i].number.E164 < candidates[j].number.E164
	})
	c := candidates[0]
	return &c.number, &c.gateway, nil, nil
}

func (s *Service) findCallConflict(ctx context.Context, tenantID string, phoneNumber *string, gatewayNumber string) *model.Verification {
	if phoneNumber == nil {
		return nil
	}
	sessions, _ := s.store.PendingSessionsByPhone(ctx, tenantID, *phoneNumber)
	for _, v := range sessions {
		if v.IsCall() && v.GatewayNumber == gatewayNumber {
			return v
		}
	}
	return nil
}

func (s *Service) generateCode(spec *createSpec) (string, []byte, error) {
	length := spec.CodeLength
	alphabet := code.DefaultDigits
	if spec.Mode == model.ModeCodeFirst {
		switch spec.CodeFormat {
		case "digits":
			alphabet = code.DefaultDigits
		case "words":
			value, err := code.RandomWords(spec.CodeWords, codeWords)
			if err != nil {
				return "", nil, err
			}
			return value, code.Hash(value), nil
		default:
			alphabet = s.cfg.Verification.CodeFirst.CodeAlphabet
		}
	}
	value, err := code.Random(length, alphabet)
	if err != nil {
		return "", nil, err
	}
	return value, code.Hash(value), nil
}

func (s *Service) makeLinks(v *model.Verification, plainCode string) *LinkSet {
	if s.links == nil || !s.links.Enabled() {
		return nil
	}
	token, err := s.links.New(v.ID, plainCode, v.ExpiresAt)
	if err != nil {
		return nil
	}
	out := &LinkSet{PageURL: token.PageURL, QRURL: token.QRURL}
	if v.IsSMS() && plainCode != "" {
		sms := "sms:" + v.GatewayNumber + "?body=" + links.EscapeBody(plainCode)
		ios := "sms:" + v.GatewayNumber + "&body=" + links.EscapeBody(plainCode)
		out.SMSURI = &sms
		out.SMSURIIOS = &ios
		smsQR := token.QRURL + "?kind=sms"
		out.SMSQRURL = &smsQR
	}
	if v.IsCall() {
		tel := "tel:" + v.GatewayNumber
		out.TelURI = &tel
		telQR := token.QRURL + "?kind=call"
		out.TelQRURL = &telQR
	}
	return out
}

func instructions(locale, method, plainCode, gatewayNumber string) string {
	if locale == "en" {
		switch method {
		case model.MethodCall:
			return "Call " + gatewayNumber + " and hang up"
		case model.MethodAny:
			return "Send SMS with code " + plainCode + " to " + gatewayNumber + ", or call and hang up"
		default:
			return "Send SMS with code " + plainCode + " to " + gatewayNumber
		}
	}
	switch method {
	case model.MethodCall:
		return "Позвоните на номер " + gatewayNumber + " и сбросьте вызов"
	case model.MethodAny:
		return "Отправьте SMS с кодом " + plainCode + " на номер " + gatewayNumber + " или позвоните и сбросьте вызов"
	default:
		return "Отправьте SMS с кодом " + plainCode + " на номер " + gatewayNumber
	}
}

func (s *Service) Get(ctx context.Context, tenantID, id string) (*model.Verification, error) {
	// Keep polling accurate even between janitor ticks.
	_, _ = s.store.Expire(ctx, s.now().UTC())
	v, err := s.store.Session(ctx, tenantID, id)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			return nil, errNotFound()
		}
		return nil, errInternal("Ошибка хранилища")
	}
	return v, nil
}

func (s *Service) Cancel(ctx context.Context, tenantID, id string) (*model.Verification, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := s.now().UTC()
	s.expireLocked(ctx, now)
	v, err := s.store.Session(ctx, tenantID, id)
	if err != nil {
		return nil, errNotFound()
	}
	switch v.Status {
	case model.StatusPending:
		v.Status = model.StatusCancelled
		if err := s.store.UpdateSession(ctx, v); err != nil {
			return nil, errInternal("Ошибка хранилища")
		}
		s.log.Info("verification.cancelled", "tenant", tenantID, "verification_id", v.ID)
		return v, nil
	case model.StatusCancelled:
		return v, nil
	default:
		return nil, errConflict("verification_already_pending", "Заявка уже завершена")
	}
}

func (s *Service) Retry(ctx context.Context, tenantID, id, clientIP, requestID string) (*Created, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := s.now().UTC()
	s.expireLocked(ctx, now)
	old, err := s.store.Session(ctx, tenantID, id)
	if err != nil {
		return nil, errNotFound()
	}
	if old.Status != model.StatusPending {
		return nil, errConflict("verification_already_pending", "Повторить можно только pending-заявку")
	}
	base := old.LastRetryAt
	if base.IsZero() {
		base = old.CreatedAt
	}
	if interval := s.cfg.Verification.RetryInterval.Std(); interval > 0 && now.Sub(base) < interval {
		return nil, errRateLimited(interval - now.Sub(base))
	}
	req := CreateRequest{
		Mode:        old.Mode,
		Method:      old.Method,
		Purpose:     old.Purpose,
		Locale:      old.Locale,
		TTLSeconds:  int(old.ExpiresAt.Sub(old.CreatedAt).Seconds()),
		Metadata:    old.Metadata,
		CallbackURL: old.CallbackURL,
	}
	if old.Phone != nil {
		req.Phone = *old.Phone
	}
	spec, err := s.specFor(tenantID, req)
	if err != nil {
		return nil, err
	}
	previousRetryAt := old.LastRetryAt
	old.LastRetryAt = now
	old.Status = model.StatusCancelled
	if err := s.store.UpdateSession(ctx, old); err != nil {
		return nil, errInternal("Ошибка хранилища")
	}
	created, err := s.createLocked(ctx, spec, clientIP, requestID, now, false)
	if err != nil {
		old.Status = model.StatusPending
		old.LastRetryAt = previousRetryAt
		_ = s.store.UpdateSession(ctx, old)
		return nil, err
	}
	created.Verification.LastRetryAt = now
	_ = s.store.UpdateSession(ctx, created.Verification)
	return created, nil
}

func (s *Service) List(ctx context.Context, filter model.SessionFilter) (*ListResponse, error) {
	_, _ = s.store.Expire(ctx, s.now().UTC())
	items, _, err := s.store.ListSessions(ctx, filter)
	if err != nil {
		if strings.Contains(err.Error(), "cursor") {
			return nil, errBad("invalid_request", "Некорректный cursor")
		}
		return nil, errInternal("Ошибка хранилища")
	}
	resp := &ListResponse{Items: make([]*VerificationResponse, 0, len(items))}
	for _, v := range items {
		resp.Items = append(resp.Items, VerificationToResponse(v, nil))
	}
	if len(items) == normalizedLimit(filter.Limit) {
		cursor := store.CursorFor(items[len(items)-1])
		resp.NextCursor = &cursor
	}
	return resp, nil
}

func normalizedLimit(n int) int {
	if n <= 0 {
		return 50
	}
	if n > 200 {
		return 200
	}
	return n
}

func (s *Service) Expire(ctx context.Context) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.expireLocked(ctx, s.now().UTC())
}

func (s *Service) expireLocked(ctx context.Context, now time.Time) {
	expired, err := s.store.Expire(ctx, now)
	if err != nil {
		s.log.Error("verification.expire_failed", "error", err)
		return
	}
	for _, v := range expired {
		s.log.Info("verification.expired", "tenant", v.TenantID, "verification_id", v.ID)
	}
}

func VerificationToResponse(v *model.Verification, created *Created) *VerificationResponse {
	if v == nil {
		return nil
	}
	out := &VerificationResponse{
		ID:            v.ID,
		Mode:          v.Mode,
		Status:        v.Status,
		Method:        v.Method,
		Phone:         v.Phone,
		GatewayNumber: v.GatewayNumber,
		CreatedAt:     v.CreatedAt.UTC(),
		ExpiresAt:     v.ExpiresAt.UTC(),
		VerifiedAt:    v.VerifiedAt,
		PollAfterMS:   pollAfterMS,
		Metadata:      v.Metadata,
	}
	if v.Reason != "" {
		reason := v.Reason
		out.Reason = &reason
	}
	if created != nil {
		out.Code = created.Code
		out.Instructions = created.Instructions
		out.Links = created.Links
	}
	return out
}

func maskPtr(p *string) string {
	if p == nil {
		return ""
	}
	return phone.Mask(*p)
}

func contains(values []string, value string) bool {
	for _, v := range values {
		if v == value {
			return true
		}
	}
	return false
}
