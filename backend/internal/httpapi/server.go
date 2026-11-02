package httpapi

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"

	"phonegate/backend/internal/config"
	"phonegate/backend/internal/core"
	"phonegate/backend/internal/model"
	"phonegate/backend/internal/phone"
)

type Server struct {
	cfg   *config.Config
	svc   *core.Service
	log   *slog.Logger
	mux   *http.ServeMux
	start time.Time

	tenantsByHash map[string]string
	trusted       []*net.IPNet
}

func New(cfg *config.Config, svc *core.Service, log *slog.Logger) *Server {
	if log == nil {
		log = slog.Default()
	}
	s := &Server{
		cfg:           cfg,
		svc:           svc,
		log:           log,
		mux:           http.NewServeMux(),
		start:         time.Now().UTC(),
		tenantsByHash: make(map[string]string),
	}
	for id, tenant := range cfg.Tenants {
		if !tenant.Enabled {
			continue
		}
		if tenant.Auth.APIKeySHA256 != "" {
			s.tenantsByHash[tenant.Auth.APIKeySHA256] = id
		}
		if tenant.Auth.APIKeySHA256Previous != "" {
			s.tenantsByHash[tenant.Auth.APIKeySHA256Previous] = id
		}
	}
	for _, raw := range cfg.Server.TrustedProxies {
		if _, network, err := net.ParseCIDR(raw); err == nil {
			s.trusted = append(s.trusted, network)
		}
	}
	s.routes()
	return s
}

func (s *Server) Handler() http.Handler {
	return s.recoverer(s.accessLog(s.requestID(s.mux)))
}

func (s *Server) routes() {
	s.mux.HandleFunc("GET /healthz", s.healthz)
	s.mux.HandleFunc("GET /readyz", s.readyz)
	s.mux.HandleFunc("GET /api/v1/healthz", s.healthz)
	s.mux.HandleFunc("GET /api/v1/readyz", s.readyz)

	s.mux.HandleFunc("POST "+s.cfg.Gateway.ExchangePath, s.exchange)
	s.mux.HandleFunc("POST "+s.cfg.Gateway.OutgoingPath, s.outgoingReport)

	s.mux.HandleFunc("POST /api/v1/verifications", s.createVerification)
	s.mux.HandleFunc("GET /api/v1/verifications", s.listVerifications)
	s.mux.HandleFunc("GET /api/v1/verifications/{id}", s.getVerification)
	s.mux.HandleFunc("POST /api/v1/verifications/{id}/cancel", s.cancelVerification)
	s.mux.HandleFunc("POST /api/v1/verifications/{id}/retry", s.retryVerification)
	s.mux.HandleFunc("GET /api/v1/me", s.me)

	// Demo page (no auth required, uses public API base from config)
	s.mux.HandleFunc("GET /demo", s.demoPage)

	if s.svc.Links() != nil && s.svc.Links().Enabled() {
		base := strings.TrimRight(s.cfg.Verification.Links.PagePath, "/")
		s.mux.HandleFunc("GET "+base+"/{token}", s.publicPage)
		s.mux.HandleFunc("GET "+base+"/{token}/status", s.publicStatus)
		s.mux.HandleFunc("GET "+base+"/{token}/qr.png", s.publicQR)
		s.mux.HandleFunc("GET "+base+"/{token}/go/{kind}", s.publicAction)
	}
}

func (s *Server) healthz(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	_, _ = io.WriteString(w, "ok")
}

func (s *Server) readyz(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()
	if err := s.svc.Store().Ping(ctx); err != nil {
		w.WriteHeader(http.StatusServiceUnavailable)
		return
	}
	w.WriteHeader(http.StatusOK)
}

func (s *Server) me(w http.ResponseWriter, r *http.Request) {
	tenantID, ok := s.authTenant(w, r)
	if !ok {
		return
	}
	info, ok := s.svc.TenantInfo(tenantID)
	if !ok {
		writeError(w, r, http.StatusUnauthorized, "unauthorized", "Арендатор не найден", 0)
		return
	}
	writeJSON(w, http.StatusOK, info)
}

func (s *Server) createVerification(w http.ResponseWriter, r *http.Request) {
	tenantID, ok := s.authTenant(w, r)
	if !ok {
		return
	}
	var req core.CreateRequest
	if err := decodeJSON(w, r, &req, s.cfg.Server.MaxBodyBytes, true); err != nil {
		writeError(w, r, http.StatusBadRequest, "invalid_request", err.Error(), 0)
		return
	}
	replace, _ := strconv.ParseBool(r.URL.Query().Get("replace"))
	created, err := s.svc.Create(r.Context(), tenantID, req, clientIP(r, s.trusted), requestID(r), r.Header.Get("Idempotency-Key"), replace)
	if err != nil {
		s.writeCoreError(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, core.VerificationToResponse(created.Verification, created))
}

func (s *Server) getVerification(w http.ResponseWriter, r *http.Request) {
	tenantID, ok := s.authTenant(w, r)
	if !ok {
		return
	}
	v, err := s.svc.Get(r.Context(), tenantID, r.PathValue("id"))
	if err != nil {
		s.writeCoreError(w, r, err)
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, core.VerificationToResponse(v, nil))
}

func (s *Server) cancelVerification(w http.ResponseWriter, r *http.Request) {
	tenantID, ok := s.authTenant(w, r)
	if !ok {
		return
	}
	v, err := s.svc.Cancel(r.Context(), tenantID, r.PathValue("id"))
	if err != nil {
		s.writeCoreError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, core.VerificationToResponse(v, nil))
}

func (s *Server) retryVerification(w http.ResponseWriter, r *http.Request) {
	tenantID, ok := s.authTenant(w, r)
	if !ok {
		return
	}
	created, err := s.svc.Retry(r.Context(), tenantID, r.PathValue("id"), clientIP(r, s.trusted), requestID(r))
	if err != nil {
		s.writeCoreError(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, core.VerificationToResponse(created.Verification, created))
}

func (s *Server) listVerifications(w http.ResponseWriter, r *http.Request) {
	tenantID, ok := s.authTenant(w, r)
	if !ok {
		return
	}
	q := r.URL.Query()
	limit := 50
	if raw := q.Get("limit"); raw != "" {
		n, err := strconv.Atoi(raw)
		if err != nil || n < 1 || n > 200 {
			writeError(w, r, http.StatusBadRequest, "invalid_request", "limit должен быть 1..200", 0)
			return
		}
		limit = n
	}
	var from, to time.Time
	if raw := q.Get("from"); raw != "" {
		t, err := time.Parse(time.RFC3339, raw)
		if err != nil {
			writeError(w, r, http.StatusBadRequest, "invalid_request", "Некорректный from", 0)
			return
		}
		from = t
	}
	if raw := q.Get("to"); raw != "" {
		t, err := time.Parse(time.RFC3339, raw)
		if err != nil {
			writeError(w, r, http.StatusBadRequest, "invalid_request", "Некорректный to", 0)
			return
		}
		to = t
	}
	mode := q.Get("mode")
	if mode != "" && mode != model.ModePhoneFirst && mode != model.ModeCodeFirst {
		writeError(w, r, http.StatusBadRequest, "invalid_request", "Некорректный mode", 0)
		return
	}
	status := q.Get("status")
	if status != "" && !validStatus(status) {
		writeError(w, r, http.StatusBadRequest, "invalid_request", "Некорректный status", 0)
		return
	}
	var phoneValue string
	if raw := q.Get("phone"); raw != "" {
		p, err := phoneNormalize(raw)
		if err != nil {
			writeError(w, r, http.StatusBadRequest, "invalid_phone", "Некорректный телефон", 0)
			return
		}
		phoneValue = p
	}
	resp, err := s.svc.List(r.Context(), model.SessionFilter{
		TenantID: tenantID,
		Mode:     mode,
		Status:   status,
		Phone:    phoneValue,
		From:     from,
		To:       to,
		Limit:    limit,
		Cursor:   q.Get("cursor"),
	})
	if err != nil {
		s.writeCoreError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, resp)
}

func (s *Server) exchange(w http.ResponseWriter, r *http.Request) {
	gateway, ok := s.authGateway(w, r)
	if !ok {
		return
	}
	body, err := readBody(w, r, s.cfg.Server.MaxBodyBytes)
	if err != nil {
		writeError(w, r, http.StatusBadRequest, "invalid_request", err.Error(), 0)
		return
	}
	if s.cfg.Logging.LogGatewayPayload {
		s.log.Info("exchange.payload",
			"request_id", requestID(r),
			"gateway", gateway.ID,
			"bytes", len(body),
			"body", truncateLog(string(body), maxPayloadLogRunes),
		)
	}
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(body, &raw); err != nil {
		writeError(w, r, http.StatusBadRequest, "invalid_request", "Некорректный JSON", 0)
		return
	}
	req, err := core.ParseExchange(raw, s.cfg.Gateway.MaxBodyEvents.SMS, s.cfg.Gateway.MaxBodyEvents.Calls)
	if err != nil {
		s.writeCoreError(w, r, err)
		return
	}
	if strings.TrimSpace(req.SyncID) == "" {
		writeError(w, r, http.StatusBadRequest, "invalid_request", "Требуется sync_id", 0)
		return
	}
	if err := s.svc.HandleExchange(r.Context(), gateway, req); err != nil {
		s.writeCoreError(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) outgoingReport(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.authGateway(w, r); !ok {
		return
	}
	if _, err := readBody(w, r, s.cfg.Server.MaxBodyBytes); err != nil {
		writeError(w, r, http.StatusBadRequest, "invalid_request", err.Error(), 0)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) authTenant(w http.ResponseWriter, r *http.Request) (string, bool) {
	raw := strings.TrimSpace(r.Header.Get("Authorization"))
	token := ""
	if strings.HasPrefix(strings.ToLower(raw), "bearer ") {
		token = strings.TrimSpace(raw[7:])
	}
	if token == "" {
		w.Header().Set("WWW-Authenticate", "Bearer")
		writeError(w, r, http.StatusUnauthorized, "unauthorized", "Требуется API-ключ", 0)
		return "", false
	}
	sum := sha256.Sum256([]byte(token))
	hash := hex.EncodeToString(sum[:])
	tenantID, ok := s.tenantsByHash[hash]
	if !ok {
		w.Header().Set("WWW-Authenticate", "Bearer")
		writeError(w, r, http.StatusUnauthorized, "unauthorized", "Неверный API-ключ", 0)
		return "", false
	}
	tenant, ok := s.cfg.Tenant(tenantID)
	if !ok {
		writeError(w, r, http.StatusForbidden, "forbidden", "Арендатор выключен", 0)
		return "", false
	}
	if !ipAllowed(clientIP(r, s.trusted), tenant.Auth.IPAllowlist) {
		writeError(w, r, http.StatusForbidden, "forbidden", "IP не разрешён", 0)
		return "", false
	}
	return tenantID, true
}

func (s *Server) authGateway(w http.ResponseWriter, r *http.Request) (*model.Gateway, bool) {
	var token string
	for _, header := range s.cfg.Gateway.AuthHeaders {
		raw := strings.TrimSpace(r.Header.Get(header))
		if raw == "" {
			continue
		}
		if strings.HasPrefix(strings.ToLower(raw), "bearer ") {
			raw = strings.TrimSpace(raw[7:])
		}
		token = raw
		break
	}
	if token == "" {
		w.Header().Set("WWW-Authenticate", "Bearer")
		writeError(w, r, http.StatusUnauthorized, "unauthorized", "Требуется токен шлюза", 0)
		return nil, false
	}
	sum := sha256.Sum256([]byte(token))
	hash := hex.EncodeToString(sum[:])
	gw, err := s.svc.Store().GatewayByTokenHash(r.Context(), hash)
	if err != nil {
		w.Header().Set("WWW-Authenticate", "Bearer")
		writeError(w, r, http.StatusUnauthorized, "unauthorized", "Неверный токен шлюза", 0)
		return nil, false
	}
	if gw.Status == model.GatewayDisabled {
		writeError(w, r, http.StatusForbidden, "gateway_disabled", "Шлюз отключён", 0)
		return nil, false
	}
	return gw, true
}

func (s *Server) writeCoreError(w http.ResponseWriter, r *http.Request, err error) {
	var apiErr *core.Error
	if errors.As(err, &apiErr) {
		if apiErr.Existing != nil {
			body := map[string]any{
				"error": map[string]any{
					"code":       apiErr.Code,
					"message":    apiErr.Message,
					"request_id": requestID(r),
				},
				"verification": core.VerificationToResponse(apiErr.Existing, nil),
			}
			writeJSON(w, apiErr.Status, body)
			return
		}
		writeError(w, r, apiErr.Status, apiErr.Code, apiErr.Message, apiErr.RetryAfter)
		return
	}
	s.log.Error("http.unhandled_error", "error", err)
	writeError(w, r, http.StatusInternalServerError, "internal", "Внутренняя ошибка", 0)
}

func validStatus(value string) bool {
	switch value {
	case model.StatusPending, model.StatusVerified, model.StatusExpired, model.StatusCancelled, model.StatusFailed:
		return true
	default:
		return false
	}
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func writeError(w http.ResponseWriter, r *http.Request, status int, code, message string, retry time.Duration) {
	obj := map[string]any{
		"code":       code,
		"message":    message,
		"request_id": requestID(r),
	}
	if retry > 0 {
		obj["retry_after_ms"] = retry.Milliseconds()
		w.Header().Set("Retry-After", strconv.Itoa(int((retry+time.Second-1)/time.Second)))
	}
	writeJSON(w, status, map[string]any{"error": obj})
}

func decodeJSON(w http.ResponseWriter, r *http.Request, dst any, max int64, strict bool) error {
	ct := r.Header.Get("Content-Type")
	if ct != "" && !strings.Contains(strings.ToLower(ct), "application/json") {
		return fmt.Errorf("Content-Type должен быть application/json")
	}
	body, err := readBody(w, r, max)
	if err != nil {
		return err
	}
	dec := json.NewDecoder(strings.NewReader(string(body)))
	if strict {
		dec.DisallowUnknownFields()
	}
	if err := dec.Decode(dst); err != nil {
		return fmt.Errorf("Некорректный JSON")
	}
	var extra any
	if err := dec.Decode(&extra); err != io.EOF {
		return fmt.Errorf("Лишние данные после JSON")
	}
	return nil
}

func readBody(w http.ResponseWriter, r *http.Request, max int64) ([]byte, error) {
	if max <= 0 {
		max = 1 << 20
	}
	r.Body = http.MaxBytesReader(w, r.Body, max)
	body, err := io.ReadAll(r.Body)
	if err != nil {
		return nil, fmt.Errorf("Тело запроса слишком велико")
	}
	return body, nil
}

func requestID(r *http.Request) string {
	if v, ok := r.Context().Value(ctxRequestID).(string); ok {
		return v
	}
	return ""
}

type ctxKey string

const ctxRequestID ctxKey = "request_id"

func (s *Server) requestID(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		id := strings.TrimSpace(r.Header.Get("X-Request-Id"))
		if id == "" || len(id) > 128 || !safeHeader(id) {
			id = core.NewID("req_")
		}
		w.Header().Set("X-Request-Id", id)
		ctx := context.WithValue(r.Context(), ctxRequestID, id)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func safeHeader(value string) bool {
	for _, r := range value {
		if r < 0x20 || r > 0x7e {
			return false
		}
	}
	return true
}

type statusWriter struct {
	http.ResponseWriter
	status int
	bytes  int
}

func (w *statusWriter) WriteHeader(status int) {
	w.status = status
	w.ResponseWriter.WriteHeader(status)
}

func (w *statusWriter) Write(p []byte) (int, error) {
	if w.status == 0 {
		w.status = http.StatusOK
	}
	n, err := w.ResponseWriter.Write(p)
	w.bytes += n
	return n, err
}

func (s *Server) accessLog(next http.Handler) http.Handler {
	if !s.cfg.Logging.AccessLog {
		return next
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		sw := &statusWriter{ResponseWriter: w}
		next.ServeHTTP(sw, r)
		status := sw.status
		if status == 0 {
			status = http.StatusOK
		}
		s.log.Info("http.request",
			"request_id", requestID(r),
			"method", r.Method,
			"path", safePath(r.URL.Path),
			"status", status,
			"duration_ms", time.Since(start).Milliseconds(),
			"bytes", sw.bytes,
			"ip", clientIP(r, s.trusted),
		)
	})
}

func safePath(path string) string {
	parts := strings.Split(path, "/")
	for i, part := range parts {
		if strings.HasPrefix(part, "vt_") {
			parts[i] = "***"
		}
	}
	return strings.Join(parts, "/")
}

func (s *Server) recoverer(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if rec := recover(); rec != nil {
				s.log.Error("http.panic", "panic", rec, "request_id", requestID(r))
				writeError(w, r, http.StatusInternalServerError, "internal", "Внутренняя ошибка", 0)
			}
		}()
		next.ServeHTTP(w, r)
	})
}

func clientIP(r *http.Request, trusted []*net.IPNet) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	if len(trusted) > 0 {
		remote := net.ParseIP(host)
		ok := false
		for _, n := range trusted {
			if remote != nil && n.Contains(remote) {
				ok = true
				break
			}
		}
		if ok {
			if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
				first := strings.TrimSpace(strings.Split(xff, ",")[0])
				if net.ParseIP(first) != nil {
					return first
				}
			}
		}
	}
	return host
}

func ipAllowed(ip string, allow []string) bool {
	if len(allow) == 0 {
		return true
	}
	parsed := net.ParseIP(ip)
	if parsed == nil {
		return false
	}
	for _, raw := range allow {
		if _, network, err := net.ParseCIDR(raw); err == nil {
			if network.Contains(parsed) {
				return true
			}
			continue
		}
		if candidate := net.ParseIP(raw); candidate != nil && candidate.Equal(parsed) {
			return true
		}
	}
	return false
}

func phoneNormalize(raw string) (string, error) {
	return phone.Normalize(raw)
}

// maxPayloadLogRunes bounds the raw gateway body written to the log when
// logging.log_gateway_payload is enabled. A huge exchange must not produce a
// single multi-megabyte log record.
const maxPayloadLogRunes = 32 * 1024

func truncateLog(value string, max int) string {
	if max <= 0 {
		return value
	}
	runes := []rune(value)
	if len(runes) <= max {
		return value
	}
	return string(runes[:max]) + "…(truncated)"
}
