// Package contracts owns versioned boundaries shared by the CLI, store, and
// external subprocesses.
package contracts

const (
	ConfigSchema        = "ygg.config/v1"
	ExtractorSchema     = "ygg.extractor/v1"
	EmbeddingSchema     = "ygg.embedding/v1"
	IndexProgressSchema = "ygg.index.progress/v1"
	SearchSchema        = "ygg.search.result/v5"
	CLIEnvelopeSchema   = "ygg.cli/v1"
)

// SearchRecord is the sole canonical output of extraction.
type SearchRecord struct {
	ID        string         `json:"id,omitempty"`
	Path      string         `json:"path"`
	StartLine int            `json:"startLine"`
	EndLine   int            `json:"endLine"`
	Kind      string         `json:"kind"`
	Title     string         `json:"title,omitempty"`
	Text      string         `json:"text"`
	Metadata  map[string]any `json:"metadata,omitempty"`
	Source    string         `json:"source,omitempty"`
}
