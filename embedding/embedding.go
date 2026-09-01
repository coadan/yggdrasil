// Package embedding defines the provider-neutral semantic vector capability.
//
// Implementations own transport and validation. Consumers own provider
// construction, cancellation, and lifetime.
package embedding

import "context"

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
