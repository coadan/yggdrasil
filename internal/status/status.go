// Package status inspects index state without mutating it.
package status

import (
	"context"
	"errors"
	"os"

	"github.com/coadan/yggdrasil/internal/config"
	"github.com/coadan/yggdrasil/internal/discovery"
	"github.com/coadan/yggdrasil/internal/embedding"
	"github.com/coadan/yggdrasil/internal/project"
	"github.com/coadan/yggdrasil/internal/store"
)

type Result struct {
	Root      string                `json:"root"`
	RootID    string                `json:"rootId"`
	Database  string                `json:"database"`
	Indexed   bool                  `json:"indexed"`
	Counts    store.Counts          `json:"counts"`
	Freshness Freshness             `json:"freshness"`
	Embedding *store.EmbeddingState `json:"embedding,omitempty"`
	LatestRun *store.Run            `json:"latestRun,omitempty"`
}

type Freshness struct {
	New       int `json:"new"`
	Modified  int `json:"modified"`
	Deleted   int `json:"deleted"`
	Unchanged int `json:"unchanged"`
}

func Inspect(ctx context.Context, paths project.Paths, cfg config.Config) (Result, error) {
	result := Result{Root: paths.Root, RootID: paths.ID, Database: paths.Database}
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
	present := make(map[string]bool, len(candidates))
	for _, candidate := range candidates {
		present[candidate.Path] = true
		state, exists, err := value.FileState(ctx, candidate.Path)
		if err != nil {
			return Result{}, err
		}
		switch {
		case !exists:
			result.Freshness.New++
		case state.Size != candidate.Size ||
			state.MTimeNS != candidate.MTimeNS ||
			state.ExtractionFingerprint != fingerprint:
			result.Freshness.Modified++
		default:
			result.Freshness.Unchanged++
		}
	}
	existing, err := value.FilePaths(ctx)
	if err != nil {
		return Result{}, err
	}
	for _, path := range existing {
		if !present[path] {
			result.Freshness.Deleted++
		}
	}
	return result, nil
}
