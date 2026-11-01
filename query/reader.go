package query

import (
	"context"

	"github.com/coadan/yggdrasil/query/model"
)

// Candidate is an indexed retrieval fact consumed by ranking. Storage-specific
// row handles remain ordinary values and are never exposed as database handles.
type Candidate = model.Candidate
type EmbeddingState = model.EmbeddingState

// Reader is the narrow immutable snapshot consumed by repository ranking.
// Implementations must return promptly or honor context cancellation.
type Reader interface {
	LiteralCandidates(context.Context, string, string, string, int) ([]Candidate, error)
	LexicalCandidates(context.Context, string, string, int) ([]Candidate, error)
	LexicalRecords(context.Context, string, string) ([]Candidate, error)
	PathCandidates(context.Context, []string, string, int) ([]Candidate, error)
	ExtractorCandidates(context.Context, string, string, int) ([]Candidate, error)
	GraphCandidates(context.Context, []string, string, int) ([]Candidate, error)
	EmbeddingStateForScope(context.Context, string, string) (EmbeddingState, error)
	VectorCandidates(context.Context, []float32, string, int) ([]Candidate, error)
}
