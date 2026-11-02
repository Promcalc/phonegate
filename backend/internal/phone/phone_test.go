package phone

import "testing"

func TestNormalize(t *testing.T) {
	tests := []struct {
		in   string
		want string
		ok   bool
	}{
		{"+7 (900) 123-45-67", "+79001234567", true},
		{"8 900 123 45 67", "+79001234567", true},
		{"9001234567", "+79001234567", true},
		{"+44 20 7946 0958", "+442079460958", true},
		{"+79001234567", "+79001234567", true},
		{"", "", false},
		{"+7 900 abc", "", false},
		{"+0123456789", "", false},
		{"123", "", false},
	}
	for _, tc := range tests {
		got, err := Normalize(tc.in)
		if tc.ok && err != nil {
			t.Fatalf("%q: unexpected error: %v", tc.in, err)
		}
		if !tc.ok && err == nil {
			t.Fatalf("%q: expected error, got %q", tc.in, got)
		}
		if got != tc.want {
			t.Fatalf("%q: got %q, want %q", tc.in, got, tc.want)
		}
	}
}

func TestMask(t *testing.T) {
	if got := Mask("+79001234567"); got != "+7900***4567" {
		t.Fatalf("mask: %q", got)
	}
	if got := Mask(""); got != "" {
		t.Fatalf("empty mask: %q", got)
	}
}
