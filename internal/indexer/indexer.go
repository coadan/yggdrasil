// Package indexer coordinates discovery, generic extraction, and persistence.
package indexer

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"os"
	"sort"
	"strconv"
	"syscall"
	"time"

	"github.com/coadan/yggdrasil/internal/chunk"
	"github.com/coadan/yggdrasil/internal/config"
	"github.com/coadan/yggdrasil/internal/discovery"
	"github.com/coadan/yggdrasil/internal/embedding"
	"github.com/coadan/yggdrasil/internal/plugin"
	"github.com/coadan/yggdrasil/internal/project"
	"github.com/coadan/yggdrasil/internal/store"
)

type Options struct {
	Full    bool
	NoEmbed bool
}

type Summary struct {
	RunID           string `json:"runId"`
	Scanned         int    `json:"scanned"`
	Indexed         int    `json:"indexed"`
	Unchanged       int    `json:"unchanged"`
	Deleted         int    `json:"deleted"`
	Skipped         int    `json:"skipped"`
	Embedded        int    `json:"embedded"`
	EmbeddingStatus string `json:"embeddingStatus"`
	Diagnostics     int    `json:"diagnostics"`
	ElapsedMS       int64  `json:"elapsedMs"`
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

	if opts.Full {
		for _, path := range []string{paths.Database, paths.Database + "-wal", paths.Database + "-shm"} {
			if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
				return Summary{}, fmt.Errorf("remove old index %s: %w", path, err)
			}
		}
	}
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
	summary.EmbeddingStatus = "unconfigured"
	if opts.NoEmbed {
		summary.EmbeddingStatus = "skipped"
	} else if cfg.Embedding != nil {
		summary.Embedded, summary.EmbeddingStatus, err = embedRecords(
			ctx, value, paths, *cfg.Embedding, summary.RunID, &summary.Diagnostics,
		)
		if err != nil {
			return summary, err
		}
	}
	return summary, nil
}

func embedRecords(
	ctx context.Context,
	value *store.Store,
	paths project.Paths,
	cfg config.Embedding,
	runID string,
	diagnostics *int,
) (int, string, error) {
	fingerprint := embedding.Fingerprint(cfg)
	if _, err := value.PrepareEmbeddingLane(ctx, fingerprint, cfg.Model, cfg.Dimensions); err != nil {
		return 0, "", fmt.Errorf("prepare embedding lane: %w", err)
	}
	inputs, err := value.MissingEmbeddingInputs(ctx, fingerprint, cfg.BatchSize)
	if err != nil {
		return 0, "", err
	}
	if len(inputs) == 0 {
		return 0, "ready", nil
	}
	provider, err := embedding.New(ctx, paths.Root, cfg)
	if err != nil {
		return 0, embeddingDiagnostic(ctx, value, runID, diagnostics, "provider", err), nil
	}
	closed := false
	defer func() {
		if !closed {
			_ = provider.Close()
		}
	}()
	embedded := 0
	for {
		request := make([]embedding.Input, len(inputs))
		byID := make(map[string]store.EmbeddingInput, len(inputs))
		for i, input := range inputs {
			id := strconv.FormatInt(input.ID, 10)
			request[i] = embedding.Input{ID: id, Text: input.Text}
			byID[id] = input
		}
		response, err := provider.Embed(ctx, request)
		if err != nil {
			return embedded, embeddingDiagnostic(ctx, value, runID, diagnostics, "request", err), nil
		}
		values := make([]store.EmbeddingValue, 0, len(response))
		for _, item := range response {
			input, ok := byID[item.ID]
			if !ok {
				err := fmt.Errorf("provider returned unknown record id %q", item.ID)
				return embedded, embeddingDiagnostic(ctx, value, runID, diagnostics, "response", err), nil
			}
			values = append(values, store.EmbeddingValue{
				ID: input.ID, InputHash: input.InputHash, Vector: item.Vector,
			})
			delete(byID, item.ID)
		}
		if len(byID) != 0 {
			err := fmt.Errorf("provider omitted %d records", len(byID))
			return embedded, embeddingDiagnostic(ctx, value, runID, diagnostics, "response", err), nil
		}
		if err := value.UpsertEmbeddings(ctx, fingerprint, cfg.Dimensions, values); err != nil {
			return embedded, "", err
		}
		embedded += len(values)
		inputs, err = value.MissingEmbeddingInputs(ctx, fingerprint, cfg.BatchSize)
		if err != nil {
			return embedded, "", err
		}
		if len(inputs) == 0 {
			break
		}
	}
	if err := provider.Close(); err != nil {
		closed = true
		return embedded, embeddingDiagnostic(ctx, value, runID, diagnostics, "close", err), nil
	}
	closed = true
	return embedded, "ready", nil
}

func embeddingDiagnostic(
	ctx context.Context,
	value *store.Store,
	runID string,
	diagnostics *int,
	stage string,
	cause error,
) string {
	*diagnostics++
	if err := value.AddDiagnostic(ctx, runID, "", "embedding:"+stage, cause.Error()); err != nil {
		return "unavailable: " + cause.Error() + "; diagnostic failed: " + err.Error()
	}
	return "unavailable: " + cause.Error()
}

type indexBusyError struct{}

func (indexBusyError) Error() string { return "another index run is active" }

func errorsNewIndexBusy() error { return indexBusyError{} }
