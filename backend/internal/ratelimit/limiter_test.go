package ratelimit

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestFirstActive(t *testing.T) {
	start := time.Date(2026, 11, 8, 12, 0, 0, 0, time.UTC)
	// Include empty histories and boundaries around powers of two; compare
	// every possible expired prefix with its expected boundary.
	for size := 0; size <= 129; size++ {
		attempts := make([]time.Time, size)
		for i := range attempts {
			attempts[i] = start.Add(time.Duration(i) * time.Second)
		}
		for expired := 0; expired <= size; expired++ {
			cutoff := start.Add(time.Duration(expired-1) * time.Second)
			if got := firstActive(attempts, cutoff); got != expired {
				t.Fatalf("size=%d expired=%d: got %d", size, expired, got)
			}
		}
	}
	duplicates := []time.Time{start, start, start, start.Add(time.Second)}
	if got := firstActive(duplicates, start); got != 3 {
		t.Fatalf("duplicate timestamps: got %d, want 3", got)
	}
}

func TestLimiterSlidingWindow(t *testing.T) {
	limiter := New(2, time.Minute, NewMemory())
	ctx := context.Background()
	start := time.Date(2026, 11, 8, 12, 0, 59, 0, time.UTC)
	for _, step := range []struct {
		key   string
		after time.Duration
		allow bool
	}{
		{"a", 0, true},
		{"a", 30 * time.Second, true},
		{"a", time.Minute - time.Nanosecond, false},
		{"b", time.Minute - time.Nanosecond, true},
		{"a", time.Minute, true},
		{"a", time.Minute, false},
		{"a", 90*time.Second - time.Nanosecond, false},
		{"a", 90 * time.Second, true},
		{"a", 90 * time.Second, false},
		{"a", 3 * time.Minute, true},
	} {
		got, err := limiter.Check(ctx, step.key, start.Add(step.after))
		if err != nil || got != step.allow {
			t.Fatalf("key=%s at %s: allowed=%v err=%v, want %v", step.key, step.after, got, err, step.allow)
		}
	}
}

func TestLimiterConcurrentRequests(t *testing.T) {
	storage := NewMemory()
	limiters := []*Limiter{New(10, time.Minute, storage), New(10, time.Minute, storage)}
	now := time.Now()
	var admitted atomic.Int32
	var wg sync.WaitGroup
	for i := 0; i < 100; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			allowed, err := limiters[i%len(limiters)].Check(context.Background(), "sender", now)
			if err != nil {
				t.Error(err)
			}
			if allowed {
				admitted.Add(1)
			}
		}()
	}
	wg.Wait()
	if got := admitted.Load(); got != 10 {
		t.Fatalf("admitted=%d, want 10", got)
	}
}

func TestLimiterReleasesExpiredBackingArray(t *testing.T) {
	storage := NewMemory().(*memory)
	limiter := New(10, time.Minute, storage)
	start := time.Now()
	old := make([]time.Time, 3, 64)
	old[0], old[1], old[2] = start, start.Add(30*time.Second), start.Add(40*time.Second)
	storage.attempts["sender"] = old

	allowed, err := limiter.Check(context.Background(), "sender", start.Add(time.Minute))
	if err != nil || !allowed {
		t.Fatalf("allowed=%v err=%v", allowed, err)
	}
	active := storage.attempts["sender"]
	if len(active) != 3 || !active[0].Equal(old[1]) || !active[1].Equal(old[2]) || !active[2].Equal(start.Add(time.Minute)) {
		t.Fatalf("unexpected active attempts: %v", active)
	}
	for i := range old {
		if &active[0] == &old[i] {
			t.Fatal("active attempts still retain the old backing array")
		}
	}
}

func TestLimiterConfiguredWindow(t *testing.T) {
	limiter := New(1, 5*time.Second, NewMemory())
	start := time.Now()
	for _, step := range []struct {
		after time.Duration
		allow bool
	}{
		{0, true},
		{5*time.Second - time.Nanosecond, false},
		{5 * time.Second, true},
	} {
		allowed, err := limiter.Check(context.Background(), "sender", start.Add(step.after))
		if err != nil || allowed != step.allow {
			t.Fatalf("at %s: allowed=%v err=%v, want %v", step.after, allowed, err, step.allow)
		}
	}
}

func TestLimiterCancelledRequest(t *testing.T) {
	limiter := New(1, time.Minute, NewMemory())
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	now := time.Now()
	allowed, err := limiter.Check(ctx, "sender", now)
	if allowed || !errors.Is(err, context.Canceled) {
		t.Fatalf("allowed=%v err=%v", allowed, err)
	}
	allowed, err = limiter.Check(context.Background(), "sender", now)
	if !allowed || err != nil {
		t.Fatalf("cancelled request consumed quota: allowed=%v err=%v", allowed, err)
	}
}

type storageFunc func(context.Context, string, func([]time.Time) []time.Time) error

func (f storageFunc) Update(ctx context.Context, key string, update func([]time.Time) []time.Time) error {
	return f(ctx, key, update)
}

func TestLimiterStorageRetry(t *testing.T) {
	now := time.Now()
	// Simulate a transaction conflict: only the last callback result commits.
	for _, finalAllowed := range []bool{true, false} {
		storage := storageFunc(func(ctx context.Context, key string, update func([]time.Time) []time.Time) error {
			full := []time.Time{now}
			if finalAllowed {
				update(full)
				update(nil)
			} else {
				update(nil)
				update(full)
			}
			return nil
		})
		limiter := New(1, time.Minute, storage)
		allowed, err := limiter.Check(context.Background(), "sender", now)
		if err != nil || allowed != finalAllowed {
			t.Fatalf("allowed=%v err=%v, want %v", allowed, err, finalAllowed)
		}
	}
}

func TestLimiterStorageCommitError(t *testing.T) {
	wantErr := errors.New("commit failed")
	storage := storageFunc(func(ctx context.Context, key string, update func([]time.Time) []time.Time) error {
		update(nil)
		return wantErr
	})
	limiter := New(1, time.Minute, storage)
	allowed, err := limiter.Check(context.Background(), "sender", time.Now())
	if allowed || !errors.Is(err, wantErr) {
		t.Fatalf("allowed=%v err=%v; want false, %v", allowed, err, wantErr)
	}
}
