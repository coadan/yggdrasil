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
	internalembedding "github.com/coadan/yggdrasil/internal/embedding"
	"github.com/coadan/yggdrasil/internal/indexer"
	"github.com/coadan/yggdrasil/internal/project"
	"github.com/coadan/yggdrasil/internal/store"
)

var ErrUnavailable = errors.New("repository index is unavailable")

type Options struct {
	Root         string
	StorageRoot  string
	IgnoreGlobs  []string
	MaxFileBytes int64
	Extractor    extractor.Provider
	Embedding    *embedding.Capability
}

type Repository struct {
	paths     project.Paths
	config    config.Config
	extractor extractor.Provider
	embedding *embedding.Capability
}

type RefreshOptions struct {
	Full          bool
	PriorityScope string
	PriorityPaths []string
	// Aging ignores priority for this refresh unit.
	Aging bool
	// EmbeddingBatches bounds vector work. Zero defaults to one batch.
	EmbeddingBatches int
}

type RefreshResult struct {
	Scanned         int
	Indexed         int
	Unchanged       int
	Deleted         int
	Skipped         int
	Embedded        int
	EmbeddingStatus string
	Diagnostics     int
	Coverage        Readiness
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
		paths: paths, config: cfg, extractor: opts.Extractor, embedding: opts.Embedding,
	}, nil
}

func (r *Repository) Root() string  { return r.paths.Root }
func (r *Repository) Scope() string { return r.paths.Scope }

func (r *Repository) Refresh(ctx context.Context, opts RefreshOptions) (RefreshResult, error) {
	if opts.EmbeddingBatches < 0 {
		return RefreshResult{}, errors.New("embedding batch limit cannot be negative")
	}
	batches := opts.EmbeddingBatches
	if batches == 0 {
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
		EnsureEmbeddings:  r.embedding != nil,
		EmbeddingProvider: provider, MaxEmbeddingBatches: batches,
		EmbeddingPriorityScope: priorityScope,
		EmbeddingPriorityPaths: priorityPaths,
		ExtractorProvider:      r.extractor,
	})
	if err != nil {
		return RefreshResult{}, err
	}
	result := RefreshResult{
		Scanned: summary.Scanned, Indexed: summary.Indexed,
		Unchanged: summary.Unchanged, Deleted: summary.Deleted,
		Skipped: summary.Skipped, Embedded: summary.Embedded,
		EmbeddingStatus: summary.EmbeddingStatus, Diagnostics: summary.Diagnostics,
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
	value, err := store.Open(ctx, r.paths.Database, r.paths.Root, r.paths.ID)
	if err != nil {
		return Readiness{}, fmt.Errorf("open repository index: %w", err)
	}
	defer value.Close()
	state, err := value.EmbeddingStateForScope(
		ctx, internalembedding.Fingerprint(*r.config.Embedding), scope,
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
	}
}
