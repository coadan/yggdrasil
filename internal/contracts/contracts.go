// Package contracts owns versioned boundaries shared by the CLI, store, and
// external subprocesses.
package contracts

import "github.com/coadan/yggdrasil/extractor"

const (
	ConfigSchema        = "ygg.config/v1"
	ExtractorSchema     = "ygg.extractor/v1"
	EmbeddingSchema     = "ygg.embedding/v1"
	IndexProgressSchema = "ygg.index.progress/v1"
	SearchSchema        = "ygg.search.result/v5"
	CLIEnvelopeSchema   = "ygg.cli/v1"
)

// SearchRecord is the sole canonical output of extraction.
type SearchRecord = extractor.Record
