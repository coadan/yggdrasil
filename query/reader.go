package query

import "context"

// Candidate is an indexed retrieval fact consumed by ranking. Storage-specific
// row handles remain ordinary values and are never exposed as database handles.
type Candidate struct {
	ID        int64
	InputHash string
	Path      string
	StartLine int
	EndLine   int
	Kind      string
	Title     string
	Text      string
	Metadata  map[string]any
	Source    string
}

type EmbeddingState struct {
	Configured  bool   `json:"configured"`
	Fingerprint string `json:"fingerprint,omitempty"`
	Model       string `json:"model,omitempty"`
	Dimensions  int    `json:"dimensions,omitempty"`
	Embedded    int    `json:"embedded"`
	Records     int    `json:"records"`
	Complete    bool   `json:"complete"`
}

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
