package core

import (
	"fmt"
	"net/http"
	"time"

	"phonegate/backend/internal/model"
)

type Error struct {
	Status     int
	Code       string
	Message    string
	RetryAfter time.Duration
	Existing   *model.Verification
}

func (e *Error) Error() string { return fmt.Sprintf("%s: %s", e.Code, e.Message) }

func errBad(code, message string) *Error {
	return &Error{Status: http.StatusBadRequest, Code: code, Message: message}
}

func errUnauthorized(message string) *Error {
	return &Error{Status: http.StatusUnauthorized, Code: "unauthorized", Message: message}
}

func errForbidden(message string) *Error {
	return &Error{Status: http.StatusForbidden, Code: "forbidden", Message: message}
}

func errNotFound() *Error {
	return &Error{Status: http.StatusNotFound, Code: "not_found", Message: "Заявка не найдена"}
}

func errConflict(code, message string) *Error {
	return &Error{Status: http.StatusConflict, Code: code, Message: message}
}

func errRateLimited(retry time.Duration) *Error {
	if retry <= 0 {
		retry = time.Minute
	}
	return &Error{Status: http.StatusTooManyRequests, Code: "rate_limited", Message: "Слишком много запросов", RetryAfter: retry}
}

func errNoGateway() *Error {
	return &Error{Status: http.StatusServiceUnavailable, Code: "no_gateway_available", Message: "Нет доступного номера шлюза"}
}

func errInternal(message string) *Error {
	return &Error{Status: http.StatusInternalServerError, Code: "internal", Message: message}
}
