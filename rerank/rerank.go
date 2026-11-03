// Package rerank defines the provider-neutral relevance-scoring capability.
//
// Implementations own transport and validation. Consumers own provider
// construction, cancellation, and lifetime.
package rerank

import "context"

const (
	// DefaultDepth is the number of leading fused results rescored when a
	// capability leaves Depth unset.
	DefaultDepth = 20
	// DefaultMaxInputChars bounds each candidate's text when a capability
	// leaves MaxInputChars unset.
	DefaultMaxInputChars = 1500
)

type Input struct {
	ID   string `json:"id"`
	Text string `json:"text"`
}

type Score struct {
	ID    string  `json:"id"`
	Score float64 `json:"score"`
}

type Provider interface {
	// Rerank scores each input's relevance to the query; higher is more
	// relevant. Calls may be retained across searches. Providers need not
	// support concurrent calls; the host owns serialization.
	Rerank(context.Context, string, []Input) ([]Score, error)
	Close() error
}

// Capability binds a retained provider to the bounded rescoring budget.
type Capability struct {
	Provider Provider
	// Depth is the number of leading fused results rescored. Zero uses
	// DefaultDepth.
	Depth int
	// MaxInputChars bounds each candidate's text in runes. Zero uses
	// DefaultMaxInputChars.
	MaxInputChars int
}
