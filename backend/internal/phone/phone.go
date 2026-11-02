// Package phone normalizes and masks phone numbers.
package phone

import (
	"errors"
	"strings"
	"unicode"
)

var ErrInvalid = errors.New("invalid phone")

// Normalize converts a phone number to E.164. It accepts common formatting
// characters (spaces, dashes, parentheses and dots) but rejects letters.
// Russian local forms 8XXXXXXXXXX and XXXXXXXXXX are converted to +7XXXXXXXXXX.
func Normalize(raw string) (string, error) {
	s := strings.TrimSpace(raw)
	if s == "" {
		return "", ErrInvalid
	}

	var b strings.Builder
	b.Grow(len(s))
	for _, r := range s {
		switch {
		case unicode.IsDigit(r):
			b.WriteRune(r)
		case r == '+':
			if b.Len() != 0 {
				return "", ErrInvalid
			}
			b.WriteRune(r)
		case r == ' ', r == '-', r == '(', r == ')', r == '.', r == '\t':
			// formatting only
		default:
			return "", ErrInvalid
		}
	}

	s = b.String()
	if s == "" {
		return "", ErrInvalid
	}

	hasPlus := strings.HasPrefix(s, "+")
	digits := strings.TrimPrefix(s, "+")
	if strings.HasPrefix(digits, "00") {
		digits = strings.TrimPrefix(digits, "00")
		hasPlus = true
	}
	if digits == "" {
		return "", ErrInvalid
	}
	for _, r := range digits {
		if r < '0' || r > '9' {
			return "", ErrInvalid
		}
	}
	// E.164 cannot start with zero.
	if digits[0] == '0' {
		return "", ErrInvalid
	}

	switch {
	case len(digits) == 11 && digits[0] == '8':
		digits = "7" + digits[1:]
	case len(digits) == 11 && digits[0] == '7':
		// already international
	case len(digits) == 10:
		// Russian local number without the country code.
		digits = "7" + digits
	case hasPlus:
		// keep as supplied
	}

	if len(digits) < 8 || len(digits) > 15 {
		return "", ErrInvalid
	}
	return "+" + digits, nil
}

// Mask hides the middle part of a phone number for logs and public pages.
func Mask(e164 string) string {
	if e164 == "" {
		return ""
	}
	digits := strings.TrimPrefix(e164, "+")
	if len(digits) <= 7 {
		if len(digits) <= 2 {
			return "***"
		}
		return "+" + digits[:1] + "***" + digits[len(digits)-1:]
	}
	// +7900***4567 style: four leading digits, three stars, four trailing.
	head := 4
	if len(digits) < 8 {
		head = 2
	}
	tail := 4
	if head+tail >= len(digits) {
		tail = 1
	}
	return "+" + digits[:head] + "***" + digits[len(digits)-tail:]
}

// CountryCode returns the leading country digits for the simple allow-list
// check used by the v1 configuration. It is deliberately conservative: the
// caller can still use a full library later without changing the API.
func CountryCode(e164 string) string {
	digits := strings.TrimPrefix(e164, "+")
	switch {
	case strings.HasPrefix(digits, "7"):
		return "RU"
	case strings.HasPrefix(digits, "1"):
		return "US"
	case strings.HasPrefix(digits, "44"):
		return "GB"
	case strings.HasPrefix(digits, "49"):
		return "DE"
	case strings.HasPrefix(digits, "33"):
		return "FR"
	case strings.HasPrefix(digits, "375"):
		return "BY"
	case strings.HasPrefix(digits, "77"):
		return "KZ"
	default:
		return ""
	}
}
