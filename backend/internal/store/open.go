package store

import "fmt"

// Open creates the configured store. B1 ships the in-memory implementation;
// SQLite and PostgreSQL are added in B2/B4 behind the same interface.
func Open(driver string) (Store, error) {
	switch driver {
	case "", "memory":
		return NewMemory(), nil
	case "sqlite", "postgres":
		return nil, fmt.Errorf("storage driver %q is planned for B2/B4; use memory for B1", driver)
	default:
		return nil, fmt.Errorf("unknown storage driver %q", driver)
	}
}
