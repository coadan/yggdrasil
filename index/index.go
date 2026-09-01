// Package index owns explicit repository index configuration, bounded refresh,
// and readiness inspection.
package index

import (
	"context"
	"errors"
	"fmt"
	"os"

	"github.com/coadan/yggdrasil/embedding"
	"github.com/coadan/yggdrasil/extractor"
	"github.com/coadan/yggdrasil/internal/config"
	"github.com/coadan/yggdrasil/internal/indexer"
	"github.com/coadan/yggdrasil/internal/project"
	"github.com/coadan/yggdrasil/internal/store"
	"github.com/coadan/yggdrasil/query"
)

var ErrUnavailable = errors.New("repository index is unavailable")
var ErrStale = errors.New("repository index is stale")

type Options struct {
	Root         string
	StorageRoot  string
	IgnoreGlobs  []string
	MaxFileBytes int64
	Extractors   []extractor.Provider
	Embedding    *embedding.Capability
}

type Repository struct {
	paths      project.Paths
	config     config.Config
	extractors []extractor.Provider
	embedding  *embedding.Capability
}

// Snapshot owns one read-only query connection.
type Snapshot struct {
	query.Reader
	close func() error
}

type RefreshOptions struct {
	Full          bool
	NoEmbed       bool
	PriorityScope string
	PriorityPaths []string
	// Aging ignores priority for this refresh unit.
	Aging bool
	// EmbeddingBatches bounds vector work. Zero defaults to one batch.
	EmbeddingBatches int
	// CompleteEmbeddings runs all remaining batches for explicit maintenance.
	CompleteEmbeddings bool
	Progress           func(Progress)
}

type RefreshResult struct {
	RunID           string    `json:"runId"`
	UpToDate        bool      `json:"upToDate,omitempty"`
	SeededFrom      string    `json:"seededFrom,omitempty"`
	Scanned         int       `json:"scanned"`
	Indexed         int       `json:"indexed"`
	Unchanged       int       `json:"unchanged"`
	Reused          int       `json:"reused"`
	Deleted         int       `json:"deleted"`
	Skipped         int       `json:"skipped"`
	Embedded        int       `json:"embedded"`
	EmbeddingStatus string    `json:"embeddingStatus"`
	Diagnostics     int       `json:"diagnostics"`
	PrunedIndexes   int       `json:"prunedIndexes,omitempty"`
	PrunedBytes     int64     `json:"prunedBytes,omitempty"`
	PruneSkipped    int       `json:"pruneSkipped,omitempty"`
	ElapsedMS       int64     `json:"elapsedMs"`
	Coverage        Readiness `json:"coverage,omitempty"`
}

type Progress struct {
	Schema          string `json:"schema"`
	Phase           string `json:"phase"`
	RunID           string `json:"runId,omitempty"`
	Completed       int    `json:"completed"`
	Total           int    `json:"total"`
	Path            string `json:"path,omitempty"`
	Indexed         int    `json:"indexed,omitempty"`
	Unchanged       int    `json:"unchanged,omitempty"`
	Reused          int    `json:"reused,omitempty"`
	Deleted         int    `json:"deleted,omitempty"`
	Skipped         int    `json:"skipped,omitempty"`
	Embedded        int    `json:"embedded,omitempty"`
	EmbeddingModel  string `json:"embeddingModel,omitempty"`
	EmbeddingStatus string `json:"embeddingStatus,omitempty"`
	Error           string `json:"error,omitempty"`
	ElapsedMS       int64  `json:"elapsedMs"`
}

type Readiness struct {
	Configured  bool
	Fingerprint string
	Model       string
	Dimensions  int
	Embedded    int
	Records     int
	Complete    bool
}

func Open(opts Options) (*Repository, error) {
	if opts.Root == "" {
		return nil, errors.New("repository root is required")
	}
	paths, err := project.ResolveAt(opts.Root, opts.StorageRoot)
	if err != nil {
		return nil, err
	}
	cfg := config.Default()
	cfg.IgnoreGlobs = append([]string(nil), opts.IgnoreGlobs...)
	if opts.MaxFileBytes > 0 {
		cfg.MaxFileBytes = opts.MaxFileBytes
	}
	if opts.Embedding != nil {
		if err := validateEmbedding(*opts.Embedding); err != nil {
			return nil, err
		}
		cfg.Embedding = embeddingConfig(*opts.Embedding)
	}
	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	return &Repository{
		paths: paths, config: cfg,
		extractors: append([]extractor.Provider(nil), opts.Extractors...),
		embedding:  opts.Embedding,
	}, nil
}

func (r *Repository) Root() string  { return r.paths.Root }
func (r *Repository) Scope() string { return r.paths.Scope }
func (r *Repository) IgnoreGlobs() []string {
	return append([]string(nil), r.config.IgnoreGlobs...)
}
func (r *Repository) MaxFileBytes() int64 { return r.config.MaxFileBytes }
func (r *Repository) HasExtractor() bool  { return len(r.extractors) > 0 }
func (r *Repository) Embedding() *embedding.Capability {
	if r.embedding == nil {
		return nil
	}
	value := *r.embedding
	return &value
}

func (s *Snapshot) Close() error { return s.close() }

// OpenSnapshot returns one current read-only index view. It never starts or
// waits for refresh; missing or stale indexes are explicit fallback signals.
func (r *Repository) OpenSnapshot(ctx context.Context) (*Snapshot, error) {
	if _, err := os.Stat(r.paths.Database); errors.Is(err, os.ErrNotExist) {
		return nil, ErrUnavailable
	} else if err != nil {
		return nil, err
	}
	value, err := store.OpenReadOnly(ctx, r.paths.Database)
	if err != nil {
		return nil, fmt.Errorf("open repository snapshot: %w", err)
	}
	current, err := indexer.FreshnessTokenWithExtractors(
		ctx, r.paths.Root, r.config, r.extractors,
	)
	if err != nil {
		value.Close()
		return nil, err
	}
	indexed, err := value.IndexFreshnessToken(ctx)
	if err != nil {
		value.Close()
		return nil, err
	}
	if current == "" || current != indexed {
		value.Close()
		return nil, ErrStale
	}
	return &Snapshot{Reader: value, close: value.Close}, nil
}

func (r *Repository) Refresh(ctx context.Context, opts RefreshOptions) (RefreshResult, error) {
	if opts.EmbeddingBatches < 0 {
		return RefreshResult{}, errors.New("embedding batch limit cannot be negative")
	}
	batches := opts.EmbeddingBatches
	if batches == 0 && !opts.CompleteEmbeddings {
		batches = 1
	}
	priorityScope := opts.PriorityScope
	priorityPaths := append([]string(nil), opts.PriorityPaths...)
	if opts.Aging {
		priorityScope = ""
		priorityPaths = nil
	}
	var provider embedding.Provider
	if r.embedding != nil {
		provider = r.embedding.Provider
	}
	summary, err := indexer.Run(ctx, r.paths, r.config, indexer.Options{
		Full: opts.Full, EnsureCurrent: !opts.Full,
		NoEmbed: opts.NoEmbed, EnsureEmbeddings: r.embedding != nil && !opts.NoEmbed,
		EmbeddingProvider: provider, MaxEmbeddingBatches: batches,
		EmbeddingPriorityScope: priorityScope,
		EmbeddingPriorityPaths: priorityPaths,
		ExtractorProviders:     r.extractors,
		Progress: func(value indexer.Progress) {
			if opts.Progress == nil {
				return
			}
			opts.Progress(Progress{
				Schema: value.Schema, Phase: value.Phase, RunID: value.RunID,
				Completed: value.Completed, Total: value.Total, Path: value.Path,
				Indexed: value.Indexed, Unchanged: value.Unchanged, Reused: value.Reused,
				Deleted: value.Deleted, Skipped: value.Skipped, Embedded: value.Embedded,
				EmbeddingModel: value.EmbeddingModel, EmbeddingStatus: value.EmbeddingStatus,
				Error: value.Error, ElapsedMS: value.ElapsedMS,
			})
		},
	})
	if err != nil {
		return RefreshResult{}, err
	}
	result := RefreshResult{
		RunID: summary.RunID, UpToDate: summary.UpToDate, SeededFrom: summary.SeededFrom,
		Scanned: summary.Scanned, Indexed: summary.Indexed,
		Unchanged: summary.Unchanged, Reused: summary.Reused, Deleted: summary.Deleted,
		Skipped: summary.Skipped, Embedded: summary.Embedded,
		EmbeddingStatus: summary.EmbeddingStatus, Diagnostics: summary.Diagnostics,
		PrunedIndexes: summary.PrunedIndexes, PrunedBytes: summary.PrunedBytes,
		PruneSkipped: summary.PruneSkipped, ElapsedMS: summary.ElapsedMS,
	}
	result.Coverage, err = r.Readiness(ctx, priorityScope)
	if err != nil && !errors.Is(err, ErrUnavailable) {
		return RefreshResult{}, err
	}
	return result, nil
}

func (r *Repository) Readiness(ctx context.Context, scope string) (Readiness, error) {
	if r.embedding == nil {
		return Readiness{}, nil
	}
	if _, err := os.Stat(r.paths.Database); errors.Is(err, os.ErrNotExist) {
		return Readiness{}, ErrUnavailable
	} else if err != nil {
		return Readiness{}, err
	}
	value, err := store.OpenReadOnly(ctx, r.paths.Database)
	if err != nil {
		return Readiness{}, fmt.Errorf("open repository index: %w", err)
	}
	defer value.Close()
	state, err := value.EmbeddingStateForScope(
		ctx, embedding.Fingerprint(*r.embedding), scope,
	)
	if err != nil {
		return Readiness{}, err
	}
	return Readiness{
		Configured: state.Configured, Fingerprint: state.Fingerprint,
		Model: state.Model, Dimensions: state.Dimensions,
		Embedded: state.Embedded, Records: state.Records, Complete: state.Complete,
	}, nil
}

func validateEmbedding(value embedding.Capability) error {
	if value.Provider == nil {
		return errors.New("embedding provider is required")
	}
	if value.ProviderFingerprint == "" {
		return errors.New("embedding provider fingerprint is required")
	}
	if value.Model == "" || value.Dimensions <= 0 {
		return errors.New("embedding model and positive dimensions are required")
	}
	return nil
}

func embeddingConfig(value embedding.Capability) *config.Embedding {
	return &config.Embedding{
		Kind: "command", Command: []string{"supplied:" + value.ProviderFingerprint},
		Model: value.Model, Dimensions: value.Dimensions,
		QueryPrefix: value.QueryPrefix, DocumentPrefix: value.DocumentPrefix,
		TimeoutMS: 10_000, BatchSize: value.BatchSize, MaxInputChars: value.MaxInputChars,
		Fingerprint: embedding.Fingerprint(value),
	}
}
