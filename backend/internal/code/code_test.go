package code

import "testing"

func TestRandomAndHash(t *testing.T) {
	value, err := Random(6, DefaultDigits)
	if err != nil {
		t.Fatal(err)
	}
	if len(value) != 6 || !Digits(value) {
		t.Fatalf("unexpected code %q", value)
	}
	if !EqualHash(Hash(value), value) {
		t.Fatal("hash should match")
	}
	lower, _ := Random(8, "ABCDEFGHJKLMNPQRSTUVWXYZ23456789")
	if !EqualHash(Hash(lower), normalizeForTest(lower)) {
		t.Fatal("alphanumeric hash should be case-insensitive")
	}
	if EqualHash(Hash(value), "000000") && value != "000000" {
		t.Fatal("different code matched")
	}
}

func TestCandidates(t *testing.T) {
	candidates := Candidates("Ваш код: 482913. Спасибо!", 6)
	if len(candidates) != 1 || candidates[0] != "482913" {
		t.Fatalf("candidates: %#v", candidates)
	}
	if got := Candidates("код K7M4Q9PX", 8); len(got) != 1 || got[0] != "K7M4Q9PX" {
		t.Fatalf("alnum candidates: %#v", got)
	}
	if got := Candidates("нет кода", 6); len(got) != 0 {
		t.Fatalf("unexpected candidates: %#v", got)
	}
}

func normalizeForTest(value string) string { return Normalize(value) }
