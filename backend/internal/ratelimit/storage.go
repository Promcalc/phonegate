package ratelimit

import (
	"context"
	"time"
)

// Storage persists timestamp histories independently of the limiting algorithm.
type Storage interface {
	// Update atomically reads, transforms and saves the history for key.
	// A missing key has an empty history; an empty result deletes the key.
	// Implementations may retry update on conflicts (e.g. Redis WATCH/MULTI).
	// The callback must be safe to repeat and must not modify existing elements
	// of its input or perform external side effects. Callbacks run synchronously.
	// A nil error means the result of the last callback invocation was committed.
	Update(ctx context.Context, key string, update func([]time.Time) []time.Time) error
}
