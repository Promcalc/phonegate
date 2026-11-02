// Package store defines the persistence contract and the in-memory
// implementation used by B0/B1. SQLite and PostgreSQL implement the same
// interface in later stages.
package store

import (
	"context"
	"errors"
	"time"

	"phonegate/backend/internal/model"
)

var (
	ErrNotFound     = errors.New("not found")
	ErrCodeConflict = errors.New("code already active on gateway number")
	ErrCallConflict = errors.New("call verification already active for phone and gateway")
	ErrNumberOwned  = errors.New("number belongs to another gateway")
)

type Store interface {
	Ping(ctx context.Context) error
	Close() error

	UpsertGateway(ctx context.Context, gw model.Gateway) error
	GatewayByTokenHash(ctx context.Context, hash string) (*model.Gateway, error)
	GatewayByID(ctx context.Context, id string) (*model.Gateway, error)
	TouchGateway(ctx context.Context, id string, at time.Time) error

	PreloadNumbers(ctx context.Context, numbers []model.GatewayNumber) error
	RegisterNumber(ctx context.Context, number model.GatewayNumber) (*model.GatewayNumber, error)
	Number(ctx context.Context, e164 string) (*model.GatewayNumber, error)
	NumbersByPools(ctx context.Context, pools []string) ([]model.GatewayNumber, error)
	TouchNumber(ctx context.Context, e164 string, at time.Time) error
	SetNumberAssigned(ctx context.Context, e164 string, at time.Time) error

	CreateSession(ctx context.Context, v *model.Verification) error
	UpdateSession(ctx context.Context, v *model.Verification) error
	Session(ctx context.Context, tenantID, id string) (*model.Verification, error)
	SessionByID(ctx context.Context, id string) (*model.Verification, error)
	PendingSessionsByGateway(ctx context.Context, gatewayNumber string) ([]*model.Verification, error)
	PendingSessionsByPhone(ctx context.Context, tenantID, phone string) ([]*model.Verification, error)
	ListSessions(ctx context.Context, filter model.SessionFilter) ([]*model.Verification, int, error)
	CountPendingByNumber(ctx context.Context, gatewayNumber string) (int, error)
	CountActiveSMSByPhone(ctx context.Context, tenantID, phone string) (int, error)
	CountPendingCodeFirst(ctx context.Context, tenantID string) (int, error)
	Expire(ctx context.Context, now time.Time) ([]*model.Verification, error)

	AddEvent(ctx context.Context, event *model.InboundEvent) (bool, error)
	AttachEvent(ctx context.Context, dedupeKey, sessionID string) error
}
