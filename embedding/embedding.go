// Package embedding defines the provider-neutral semantic vector capability.
//
// Implementations own transport and validation. Consumers own provider
// construction, cancellation, and lifetime.
package embedding

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
)

const behaviorVersion = "v3"

// Capability binds a retained provider to the immutable mechanical facts that
// identify compatible document and query vectors.
type Capability struct {
	Provider            Provider
	ProviderFingerprint string
	// IndexFingerprint identifies an existing compatible lane during migration.
	// Empty values use the deterministic supplied-capability fingerprint.
	IndexFingerprint string
	Model            string
	Dimensions       int
	QueryPrefix      string
	DocumentPrefix   string
	BatchSize        int
	MaxInputChars    int
}

type Input struct {
	ID   string `json:"id"`
	Text string `json:"text"`
}

type Value struct {
	ID     string    `json:"id"`
	Vector []float32 `json:"vector"`
}

type Provider interface {
	// Embed calls may be retained across index and query operations. Providers
	// need not support concurrent calls; the host owns serialization.
	Embed(context.Context, []Input) ([]Value, error)
	Close() error
}

// Fingerprint identifies compatible vectors produced through a supplied
// capability. It intentionally excludes the provider's runtime location.
func Fingerprint(value Capability) string {
	if value.IndexFingerprint != "" {
		return value.IndexFingerprint
	}
	maxInputChars := value.MaxInputChars
	if maxInputChars <= 0 {
		maxInputChars = 6000
	}
	data, _ := json.Marshal(struct {
		Behavior       string   `json:"behavior"`
		Kind           string   `json:"kind"`
		Command        []string `json:"command,omitempty"`
		Model          string   `json:"model"`
		Dimensions     int      `json:"dimensions"`
		DocumentPrefix string   `json:"documentPrefix,omitempty"`
		MaxInputChars  int      `json:"maxInputChars"`
	}{
		Behavior: behaviorVersion, Kind: "command",
		Command: []string{"supplied:" + value.ProviderFingerprint},
		Model:   value.Model, Dimensions: value.Dimensions,
		DocumentPrefix: value.DocumentPrefix, MaxInputChars: maxInputChars,
	})
	sum := sha256.Sum256(data)
	return "sha256:" + hex.EncodeToString(sum[:])
}
