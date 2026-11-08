// Package ratelimit provides atomic sliding-window rate limiting.
package ratelimit

import (
	"context"
	"slices"
	"time"
)

// Limiter applies the same sliding-window algorithm to any Storage.
type Limiter struct {
	limit   int
	window  time.Duration
	storage Storage
}

func New(limit int, window time.Duration, storage Storage) *Limiter {
	return &Limiter{limit: limit, window: window, storage: storage}
}

// Check atomically removes expired attempts and records the attempt if allowed.
// Rejected attempts do not extend the window.
func (l *Limiter) Check(ctx context.Context, key string, now time.Time) (bool, error) {
	allowed := false
	err := l.storage.Update(ctx, key, func(attempts []time.Time) []time.Time {
		first := firstActive(attempts, now.Add(-l.window))
		if first == len(attempts) {
			attempts = nil
		} else if first > 0 {
			// Detach so GC can reclaim the old array, including its expired prefix.
			attempts = slices.Clone(attempts[first:])
		}
		// Update may retry the callback, so overwrite the result on every call.
		allowed = len(attempts) < l.limit
		if allowed {
			attempts = append(attempts, now)
		}
		return attempts
	})
	if err != nil {
		return false, err
	}
	return allowed, nil
}

// firstActive finds the first timestamp strictly after cutoff. Timestamps are
// ordered by admission time. Probe indices 0, 1, 2, 4, 8, ... to bound the expired
// prefix, then use binary search within that range.
func firstActive(attempts []time.Time, cutoff time.Time) int {
	if len(attempts) == 0 || attempts[0].After(cutoff) {
		return 0
	}
	low, high := 1, 1
	for high < len(attempts) && !attempts[high].After(cutoff) {
		low = high + 1
		if high > (len(attempts)-1)/2 {
			high = len(attempts)
			break
		}
		high *= 2
	}
	for low < high {
		mid := low + (high-low)/2
		if attempts[mid].After(cutoff) {
			high = mid
		} else {
			low = mid + 1
		}
	}
	return low
}
