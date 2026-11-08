package ratelimit

import (
	"context"
	"testing"
	"time"
)

func TestMemoryUpdate(t *testing.T) {
	storage := NewMemory().(*memory)
	now := time.Now()
	ctx := context.Background()
	err := storage.Update(ctx, "sender", func(history []time.Time) []time.Time {
		if len(history) != 0 {
			t.Fatalf("missing key has history: %v", history)
		}
		return []time.Time{now}
	})
	if err != nil {
		t.Fatal(err)
	}
	err = storage.Update(ctx, "sender", func(history []time.Time) []time.Time {
		if len(history) != 1 || !history[0].Equal(now) {
			t.Fatalf("history was not saved: %v", history)
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, exists := storage.attempts["sender"]; exists {
		t.Fatal("empty history retained the key")
	}
}
