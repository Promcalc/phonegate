package ratelimit

import (
	"context"
	"sync"
	"time"
)

type memory struct {
	mu       sync.Mutex
	attempts map[string][]time.Time
}

func NewMemory() Storage {
	return &memory{attempts: make(map[string][]time.Time)}
}

func (m *memory) Update(ctx context.Context, key string, update func([]time.Time) []time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if err := ctx.Err(); err != nil {
		return err
	}
	attempts := update(m.attempts[key])
	if len(attempts) == 0 {
		delete(m.attempts, key)
	} else {
		m.attempts[key] = attempts
	}
	return nil
}
