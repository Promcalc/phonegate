package links

import (
	"strings"
	"testing"
	"time"
)

const secret = "0123456789abcdef0123456789abcdef"

func TestSignAndParse(t *testing.T) {
	m := NewManager("https://auth.example.test/", "/v/", secret)
	exp := time.Now().Add(time.Minute)
	token, err := m.New("vrf_test", "482913", exp)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(token.PageURL, "https://auth.example.test/v/vt_") {
		t.Fatalf("page url: %s", token.PageURL)
	}
	if token.QRURL != token.PageURL+"/qr.png" {
		t.Fatalf("qr url: %s", token.QRURL)
	}
	payload, err := m.Parse(token.Value)
	if err != nil {
		t.Fatal(err)
	}
	if payload.Session != "vrf_test" || payload.Code != "482913" {
		t.Fatalf("payload: %#v", payload)
	}
}

func TestRejectsTamperedAndExpired(t *testing.T) {
	m := NewManager("https://auth.example.test", "/v", secret)
	token, _ := m.New("vrf_test", "482913", time.Now().Add(time.Minute))
	parts := strings.Split(token.Value, ".")
	if len(parts) != 2 {
		t.Fatal("bad token")
	}
	parts[1] = parts[1][:len(parts[1])-1] + "x"
	if _, err := m.Parse(strings.Join(parts, ".")); err == nil {
		t.Fatal("tampered token accepted")
	}

	expired, _ := m.New("vrf_test", "482913", time.Now().Add(-time.Second))
	if _, err := m.Parse(expired.Value); err != ErrExpired {
		t.Fatalf("expired error = %v", err)
	}
}
