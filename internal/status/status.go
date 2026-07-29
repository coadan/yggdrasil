// Package status inspects index state without mutating it.
package status

import (
	"context"
	"errors"
	"os"
	"time"

	"github.com/coadan/yggdrasil/internal/config"
	"github.com/coadan/yggdrasil/internal/discovery"
	"github.com/coadan/yggdrasil/internal/embedding"
	"github.com/coadan/yggdrasil/internal/project"
	"github.com/coadan/yggdrasil/internal/store"
)

type Result struct {
	Version           string                `json:"version"`
	Root              string                `json:"root"`
	RootID            string                `json:"rootId"`
	Database          string                `json:"database"`
	Indexed           bool                  `json:"indexed"`
	Counts            store.Counts          `json:"counts"`
	Freshness         Freshness             `json:"freshness"`
	Configuration     Configuration         `json:"configuration"`
	GitFamily         GitFamily             `json:"gitFamily"`
	Embedding         *store.EmbeddingState `json:"embedding,omitempty"`
	EmbeddingProvider *ProviderCheck        `json:"embeddingProvider,omitempty"`
	LatestRun         *store.Run            `json:"latestRun,omitempty"`
}

type Options struct {
	Version       string
	CheckProvider bool
}

type Configuration struct {
	UserPath          string `json:"userPath"`
	UserLoaded        bool   `json:"userLoaded"`
	RepositoryPath    string `json:"repositoryPath,omitempty"`
	EmbeddingSource   string `json:"embeddingSource,omitempty"`
	EmbeddingDisabled bool   `json:"embeddingDisabled"`
}

type GitFamily struct {
	ID             string `json:"id"`
	Head           string `json:"head,omitempty"`
	AvailableSeeds int    `json:"availableSeeds"`
	RetiredIndexes int    `json:"retiredIndexes"`
	RetiredBytes   int64  `json:"retiredBytes"`
}

type ProviderCheck struct {
	Checked   bool   `json:"checked"`
	Available bool   `json:"available"`
	ElapsedMS int64  `json:"elapsedMs"`
	Error     string `json:"error,omitempty"`
}

type Freshness struct {
	New       int `json:"new"`
	Modified  int `json:"modified"`
	Deleted   int `json:"deleted"`
	Unchanged int `json:"unchanged"`
}

func Inspect(ctx context.Context, paths project.Paths, cfg config.Config, opts Options) (Result, error) {
	result := Result{
		Version:  opts.Version,
		Root:     paths.Root,
		RootID:   paths.ID,
		Database: paths.Database,
		Configuration: Configuration{
			UserPath:          cfg.UserConfigPath,
			UserLoaded:        cfg.UserConfigLoaded,
			RepositoryPath:    cfg.RepositoryConfigPath,
			EmbeddingSource:   cfg.EmbeddingSource,
			EmbeddingDisabled: cfg.EmbeddingDisabled,
		},
		GitFamily: GitFamily{ID: paths.FamilyID, Head: paths.Head},
	}
	seeds, err := project.SiblingIndexes(ctx, paths)
	if err != nil {
		return Result{}, err
	}
	result.GitFamily.AvailableSeeds = len(seeds)
	retired, err := project.RetiredIndexes(ctx, paths)
	if err != nil {
		return Result{}, err
	}
	result.GitFamily.RetiredIndexes = len(retired)
	for _, candidate := range retired {
		result.GitFamily.RetiredBytes += candidate.Bytes
	}
	if opts.CheckProvider {
		result.EmbeddingProvider = checkProvider(ctx, paths.Root, cfg.Embedding)
	}
	if _, err := os.Stat(paths.Database); errors.Is(err, os.ErrNotExist) {
		candidates, discoverErr := discovery.Candidates(paths.Root, cfg.IgnoreGlobs)
		if discoverErr != nil {
			return Result{}, discoverErr
		}
		result.Freshness.New = len(candidates)
		return result, nil
	} else if err != nil {
		return Result{}, err
	}
	value, err := store.Open(ctx, paths.Database, paths.Root, paths.ID)
	if err != nil {
		return Result{}, err
	}
	defer value.Close()
	result.Indexed = true
	result.Counts, err = value.Counts(ctx)
	if err != nil {
		return Result{}, err
	}
	result.LatestRun, err = value.LatestRun(ctx)
	if err != nil {
		return Result{}, err
	}
	if cfg.Embedding != nil {
		state, err := value.EmbeddingState(ctx, embedding.Fingerprint(*cfg.Embedding))
		if err != nil {
			return Result{}, err
		}
		result.Embedding = &state
	}
	candidates, err := discovery.Candidates(paths.Root, cfg.IgnoreGlobs)
	if err != nil {
		return Result{}, err
	}
	fingerprint := config.ExtractionFingerprint(cfg)
	states, err := value.FileStates(ctx)
	if err != nil {
		return Result{}, err
	}
	present := make(map[string]bool, len(candidates))
	for _, candidate := range candidates {
		present[candidate.Path] = true
		state, exists := states[candidate.Path]
		switch {
		case !exists:
			_, skipped, readErr := discovery.Read(paths.Root, candidate, cfg.MaxFileBytes)
			if readErr != nil || skipped == nil {
				result.Freshness.New++
			}
		case state.Size != candidate.Size ||
			state.MTimeNS != candidate.MTimeNS ||
			state.ExtractionFingerprint != fingerprint:
			result.Freshness.Modified++
		default:
			result.Freshness.Unchanged++
		}
	}
	for path := range states {
		if !present[path] {
			result.Freshness.Deleted++
		}
	}
	return result, nil
}

func checkProvider(ctx context.Context, root string, cfg *config.Embedding) *ProviderCheck {
	started := time.Now()
	result := &ProviderCheck{Checked: true}
	defer func() { result.ElapsedMS = time.Since(started).Milliseconds() }()
	if cfg == nil {
		result.Error = "unconfigured"
		return result
	}
	provider, err := embedding.New(ctx, root, *cfg)
	if err != nil {
		result.Error = err.Error()
		return result
	}
	values, embedErr := provider.Embed(ctx, []embedding.Input{{
		ID: "status-check", Text: "local embedding provider readiness",
	}})
	closeErr := provider.Close()
	switch {
	case embedErr != nil:
		result.Error = embedErr.Error()
	case closeErr != nil:
		result.Error = closeErr.Error()
	case len(values) != 1 || values[0].ID != "status-check":
		result.Error = "provider returned an invalid readiness response"
	default:
		result.Available = true
	}
	return result
}
