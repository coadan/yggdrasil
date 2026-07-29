// Package indexer coordinates discovery, generic extraction, and persistence.
package indexer

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"os"
	"sort"
	"syscall"
	"time"

	"github.com/coadan/yggdrasil/internal/chunk"
	"github.com/coadan/yggdrasil/internal/config"
	"github.com/coadan/yggdrasil/internal/discovery"
	"github.com/coadan/yggdrasil/internal/plugin"
	"github.com/coadan/yggdrasil/internal/project"
	"github.com/coadan/yggdrasil/internal/store"
)

type Options struct {
	Full bool
}

type Summary struct {
	RunID       string `json:"runId"`
	Scanned     int    `json:"scanned"`
	Indexed     int    `json:"indexed"`
	Unchanged   int    `json:"unchanged"`
	Deleted     int    `json:"deleted"`
	Skipped     int    `json:"skipped"`
	Diagnostics int    `json:"diagnostics"`
	ElapsedMS   int64  `json:"elapsedMs"`
}

func Run(ctx context.Context, paths project.Paths, cfg config.Config, opts Options) (summary Summary, err error) {
	started := time.Now()
	if err := os.MkdirAll(paths.StateDir, 0o755); err != nil {
		return Summary{}, fmt.Errorf("create state directory: %w", err)
	}
	lock, err := os.OpenFile(paths.IndexLock, os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return Summary{}, fmt.Errorf("open index lock: %w", err)
	}
	defer lock.Close()
	if err := syscall.Flock(int(lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		return Summary{}, errorsNewIndexBusy()
	}
	defer syscall.Flock(int(lock.Fd()), syscall.LOCK_UN)

	value, err := store.Open(ctx, paths.Database, paths.Root, paths.ID)
	if err != nil {
		return Summary{}, err
	}
	defer value.Close()

	summary.RunID = fmt.Sprintf("run-%d", time.Now().UnixNano())
	if err := value.BeginRun(ctx, summary.RunID); err != nil {
		return Summary{}, err
	}
	defer func() {
		status := "completed"
		if err != nil {
			status = "failed"
		}
		summary.ElapsedMS = time.Since(started).Milliseconds()
		if finishErr := value.FinishRun(context.Background(), summary.RunID, status, summary); err == nil && finishErr != nil {
			err = finishErr
		}
	}()

	candidates, err := discovery.Candidates(paths.Root, cfg.IgnoreGlobs)
	if err != nil {
		return summary, err
	}
	summary.Scanned = len(candidates)
	fingerprint := config.ExtractionFingerprint(cfg)
	plugins := plugin.NewManager(ctx, paths.Root, cfg.Plugins)
	pluginsClosed := false
	defer func() {
		if !pluginsClosed {
			plugins.Close()
		}
	}()
	present := make(map[string]bool, len(candidates))
	for _, candidate := range candidates {
		present[candidate.Path] = true
		state, exists, err := value.FileState(ctx, candidate.Path)
		if err != nil {
			return summary, err
		}
		if !opts.Full && exists &&
			state.Size == candidate.Size &&
			state.MTimeNS == candidate.MTimeNS &&
			state.ExtractionFingerprint == fingerprint {
			summary.Unchanged++
			continue
		}
		file, skipped, err := discovery.Read(paths.Root, candidate, cfg.MaxFileBytes)
		if err != nil {
			summary.Diagnostics++
			if diagnosticErr := value.AddDiagnostic(ctx, summary.RunID, candidate.Path, "read", err.Error()); diagnosticErr != nil {
				return summary, diagnosticErr
			}
			continue
		}
		if skipped != nil {
			summary.Skipped++
			if exists {
				if err := value.DeleteFile(ctx, candidate.Path); err != nil {
					return summary, err
				}
				summary.Deleted++
			}
			continue
		}
		hash := sha256.Sum256([]byte(file.Content))
		contentHash := "sha256:" + hex.EncodeToString(hash[:])
		file.ContentHash = contentHash
		records := chunk.Records(file)
		pluginRecords, pluginDiagnostics := plugins.Extract(file)
		records = append(records, pluginRecords...)
		for _, diagnostic := range pluginDiagnostics {
			summary.Diagnostics++
			if err := value.AddDiagnostic(
				ctx,
				summary.RunID,
				diagnostic.Path,
				"extractor-plugin:"+diagnostic.Plugin+":"+diagnostic.Stage,
				diagnostic.Message,
			); err != nil {
				return summary, err
			}
		}
		if err := value.ReplaceFile(ctx, summary.RunID, file, contentHash, fingerprint, records); err != nil {
			return summary, err
		}
		summary.Indexed++
	}
	existing, err := value.FilePaths(ctx)
	if err != nil {
		return summary, err
	}
	sort.Strings(existing)
	for _, path := range existing {
		if !present[path] {
			if err := value.DeleteFile(ctx, path); err != nil {
				return summary, err
			}
			summary.Deleted++
		}
	}
	for _, diagnostic := range plugins.Close() {
		summary.Diagnostics++
		if err := value.AddDiagnostic(
			ctx,
			summary.RunID,
			diagnostic.Path,
			"extractor-plugin:"+diagnostic.Plugin+":"+diagnostic.Stage,
			diagnostic.Message,
		); err != nil {
			return summary, err
		}
	}
	pluginsClosed = true
	return summary, nil
}

type indexBusyError struct{}

func (indexBusyError) Error() string { return "another index run is active" }

func errorsNewIndexBusy() error { return indexBusyError{} }
