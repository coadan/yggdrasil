// Package search ranks bounded records from the canonical SQLite index.
package search

import (
	"context"
	"errors"
	"fmt"
	"sort"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/coadan/yggdrasil/internal/config"
	"github.com/coadan/yggdrasil/internal/contracts"
	"github.com/coadan/yggdrasil/internal/embedding"
	"github.com/coadan/yggdrasil/internal/store"
)

const rrfK = 60.0

const (
	MaxResults    = 100
	MaxQueryBytes = 16 * 1024
	MaxQueryTerms = 256
)

var ErrSemanticUnavailable = errors.New("semantic search is unavailable")

type Options struct {
	Mode      string
	Limit     int
	Root      string
	Embedding *config.Embedding
}

type Result struct {
	Schema         string         `json:"schema"`
	Query          string         `json:"query"`
	RequestedMode  string         `json:"requestedMode"`
	ActiveMode     string         `json:"activeMode"`
	FallbackReason string         `json:"fallbackReason,omitempty"`
	ElapsedMS      int64          `json:"elapsedMs"`
	Records        []RankedRecord `json:"records"`
}

type RankedRecord struct {
	Path       string         `json:"path"`
	StartLine  int            `json:"startLine"`
	EndLine    int            `json:"endLine"`
	Kind       string         `json:"kind"`
	Title      string         `json:"title,omitempty"`
	Excerpt    string         `json:"excerpt"`
	Metadata   map[string]any `json:"metadata,omitempty"`
	Source     string         `json:"source"`
	Retrieval  []string       `json:"retrieval"`
	Score      float64        `json:"score"`
	internalID int64
}

func Run(ctx context.Context, value *store.Store, query string, opts Options) (Result, error) {
	started := time.Now()
	query = strings.TrimSpace(query)
	if query == "" {
		return Result{}, errors.New("search query is required")
	}
	if !utf8.ValidString(query) {
		return Result{}, errors.New("search query must be valid UTF-8")
	}
	if len(query) > MaxQueryBytes {
		return Result{}, fmt.Errorf("search query exceeds %d bytes", MaxQueryBytes)
	}
	if len(strings.Fields(query)) > MaxQueryTerms {
		return Result{}, fmt.Errorf("search query exceeds %d terms", MaxQueryTerms)
	}
	if opts.Limit == 0 {
		opts.Limit = 10
	}
	if opts.Limit < 0 || opts.Limit > MaxResults {
		return Result{}, fmt.Errorf("search limit must be between 1 and %d", MaxResults)
	}
	if opts.Mode == "" {
		opts.Mode = "auto"
	}
	if opts.Mode != "auto" && opts.Mode != "lexical" && opts.Mode != "semantic" {
		return Result{}, fmt.Errorf("unsupported search mode %q", opts.Mode)
	}
	candidateLimit := max(100, opts.Limit*10)
	var lanes []lane
	if opts.Mode != "semantic" {
		fts, err := value.LexicalCandidates(ctx, ftsQuery(query), candidateLimit)
		if err != nil {
			return Result{}, fmt.Errorf("lexical search: %w", err)
		}
		paths, err := value.PathCandidates(ctx, query, candidateLimit)
		if err != nil {
			return Result{}, fmt.Errorf("path search: %w", err)
		}
		lanes = append(lanes,
			lane{name: "lexical", records: fts},
			lane{name: "path", records: paths},
		)
	}
	result := Result{
		Schema:        contracts.SearchSchema,
		Query:         query,
		RequestedMode: opts.Mode,
		ActiveMode:    "lexical",
	}
	if opts.Mode == "lexical" {
		result.Records = fuse(opts.Limit, lanes)
		result.ElapsedMS = time.Since(started).Milliseconds()
		return result, nil
	}
	if opts.Embedding == nil {
		if opts.Mode == "semantic" {
			return Result{}, fmt.Errorf("%w: no embedding provider is configured", ErrSemanticUnavailable)
		}
		if opts.Mode == "auto" {
			result.FallbackReason = "semantic-unconfigured"
		}
		result.Records = fuse(opts.Limit, lanes)
		result.ElapsedMS = time.Since(started).Milliseconds()
		return result, nil
	}
	fingerprint := embedding.Fingerprint(*opts.Embedding)
	state, err := value.EmbeddingState(ctx, fingerprint)
	if err != nil {
		return Result{}, fmt.Errorf("inspect embedding lane: %w", err)
	}
	if !state.Complete {
		if opts.Mode == "semantic" {
			return Result{}, fmt.Errorf(
				"%w: index has %d of %d current vectors",
				ErrSemanticUnavailable, state.Embedded, state.Records,
			)
		}
		result.FallbackReason = "semantic-incomplete"
		result.Records = fuse(opts.Limit, lanes)
		result.ElapsedMS = time.Since(started).Milliseconds()
		return result, nil
	}
	provider, err := embedding.New(ctx, opts.Root, *opts.Embedding)
	if err != nil {
		return semanticFailure(result, opts, lanes, started, err)
	}
	values, embedErr := provider.Embed(ctx, []embedding.Input{{ID: "query", Text: query}})
	closeErr := provider.Close()
	if embedErr != nil {
		return semanticFailure(result, opts, lanes, started, embedErr)
	}
	if closeErr != nil {
		return semanticFailure(result, opts, lanes, started, closeErr)
	}
	if len(values) != 1 || values[0].ID != "query" {
		return semanticFailure(result, opts, lanes, started, errors.New("provider returned an invalid query embedding"))
	}
	vectors, err := value.VectorCandidates(ctx, values[0].Vector, candidateLimit)
	if err != nil {
		return Result{}, fmt.Errorf("semantic search: %w", err)
	}
	lanes = append(lanes, lane{name: "semantic", records: vectors})
	if opts.Mode == "semantic" {
		result.ActiveMode = "semantic"
	} else {
		result.ActiveMode = "hybrid"
	}
	result.Records = fuse(opts.Limit, lanes)
	result.ElapsedMS = time.Since(started).Milliseconds()
	return result, nil
}

func semanticFailure(
	result Result,
	opts Options,
	lanes []lane,
	started time.Time,
	cause error,
) (Result, error) {
	if opts.Mode == "semantic" {
		return Result{}, fmt.Errorf("%w: %v", ErrSemanticUnavailable, cause)
	}
	result.FallbackReason = "semantic-provider-error"
	result.Records = fuse(opts.Limit, lanes)
	result.ElapsedMS = time.Since(started).Milliseconds()
	return result, nil
}

type lane struct {
	name    string
	records []store.Record
}

type fused struct {
	record    store.Record
	score     float64
	retrieval map[string]bool
}

func fuse(limit int, lanes []lane) []RankedRecord {
	values := map[int64]*fused{}
	for _, candidateLane := range lanes {
		for rank, record := range candidateLane.records {
			value := values[record.ID]
			if value == nil {
				value = &fused{record: record, retrieval: map[string]bool{}}
				values[record.ID] = value
			}
			value.score += 1.0 / (rrfK + float64(rank+1))
			value.retrieval[candidateLane.name] = true
		}
	}
	ordered := make([]*fused, 0, len(values))
	for _, value := range values {
		ordered = append(ordered, value)
	}
	sort.Slice(ordered, func(i, j int) bool {
		if ordered[i].score != ordered[j].score {
			return ordered[i].score > ordered[j].score
		}
		if ordered[i].record.Path != ordered[j].record.Path {
			return ordered[i].record.Path < ordered[j].record.Path
		}
		if ordered[i].record.StartLine != ordered[j].record.StartLine {
			return ordered[i].record.StartLine < ordered[j].record.StartLine
		}
		return ordered[i].record.ID < ordered[j].record.ID
	})
	if len(ordered) > limit {
		ordered = ordered[:limit]
	}
	result := make([]RankedRecord, 0, len(ordered))
	for _, value := range ordered {
		retrieval := make([]string, 0, len(value.retrieval))
		for name := range value.retrieval {
			retrieval = append(retrieval, name)
		}
		sort.Strings(retrieval)
		result = append(result, RankedRecord{
			Path:       value.record.Path,
			StartLine:  value.record.StartLine,
			EndLine:    value.record.EndLine,
			Kind:       value.record.Kind,
			Title:      value.record.Title,
			Excerpt:    excerpt(value.record.Text),
			Metadata:   value.record.Metadata,
			Source:     value.record.Source,
			Retrieval:  retrieval,
			Score:      value.score,
			internalID: value.record.ID,
		})
	}
	return result
}

func ftsQuery(query string) string {
	fields := strings.Fields(query)
	quoted := make([]string, 0, len(fields))
	for _, field := range fields {
		field = strings.ReplaceAll(field, `"`, `""`)
		quoted = append(quoted, `"`+field+`"`)
	}
	return strings.Join(quoted, " OR ")
}

func excerpt(text string) string {
	text = strings.TrimSpace(text)
	const limit = 400
	if utf8.RuneCountInString(text) <= limit {
		return text
	}
	end := 0
	for range limit {
		_, size := utf8.DecodeRuneInString(text[end:])
		end += size
	}
	return strings.TrimSpace(text[:end]) + "…"
}
