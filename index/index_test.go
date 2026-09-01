package index

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/coadan/yggdrasil/embedding"
	"github.com/coadan/yggdrasil/query"
)

type retainedProvider struct {
	calls int
	texts []string
}

func (p *retainedProvider) Embed(_ context.Context, inputs []embedding.Input) ([]embedding.Value, error) {
	p.calls++
	values := make([]embedding.Value, len(inputs))
	for index, input := range inputs {
		p.texts = append(p.texts, input.Text)
		values[index] = embedding.Value{ID: input.ID, Vector: []float32{1, 0}}
	}
	return values, nil
}

func (p *retainedProvider) Close() error {
	panic("caller-owned provider must not be closed")
}

func TestRepositoryRefreshUsesExplicitStorageAndScopedPriority(t *testing.T) {
	root := t.TempDir()
	for path, content := range map[string]string{
		"src/owner.go":  "package owner\nfunc Owner() {}\n",
		"docs/guide.md": "# Guide\nunrelated documentation\n",
	} {
		full := filepath.Join(root, filepath.FromSlash(path))
		if err := os.MkdirAll(filepath.Dir(full), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(full, []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	explicitStorage := t.TempDir()
	t.Setenv("YGG_STORAGE_ROOT", filepath.Join(t.TempDir(), "must-not-be-used"))
	provider := &retainedProvider{}
	repository, err := Open(Options{
		Root: root, StorageRoot: explicitStorage,
		Embedding: &embedding.Capability{
			Provider: provider, ProviderFingerprint: "local-test-v1",
			Model: "test", Dimensions: 2, BatchSize: 1,
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := repository.OpenSnapshot(context.Background()); !errors.Is(err, ErrUnavailable) {
		t.Fatalf("missing snapshot err=%v", err)
	}
	result, err := repository.Refresh(context.Background(), RefreshOptions{
		PriorityScope: "src/", EmbeddingBatches: 1,
	})
	if err != nil {
		t.Fatal(err)
	}
	if provider.calls != 1 || result.EmbeddingStatus != "partial" ||
		!result.Coverage.Complete || result.Coverage.Records == 0 {
		t.Fatalf("result=%#v provider=%#v", result, provider)
	}
	global, err := repository.Readiness(context.Background(), "")
	if err != nil {
		t.Fatal(err)
	}
	if global.Complete || global.Embedded == 0 || global.Embedded >= global.Records {
		t.Fatalf("global=%#v", global)
	}
	snapshot, err := repository.OpenSnapshot(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	searchResult, err := query.Run(context.Background(), snapshot, "Owner", query.Options{
		Mode: "lexical", Scope: "src/", Limit: 5,
	})
	closeErr := snapshot.Close()
	if err != nil || closeErr != nil || len(searchResult.Records) != 1 ||
		searchResult.Records[0].Path != "src/owner.go" {
		t.Fatalf("search=%#v err=%v close=%v", searchResult, err, closeErr)
	}
	if err := os.WriteFile(filepath.Join(root, "src", "owner.go"), []byte("package changed\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := repository.OpenSnapshot(context.Background()); !errors.Is(err, ErrStale) {
		t.Fatalf("changed snapshot err=%v", err)
	}
	entries, err := os.ReadDir(filepath.Join(explicitStorage, "indexes"))
	if err != nil || len(entries) != 1 {
		t.Fatalf("explicit storage entries=%d err=%v", len(entries), err)
	}
}

type blockingProvider struct {
	started chan struct{}
	release chan struct{}
	calls   int
}

func (p *blockingProvider) Embed(_ context.Context, inputs []embedding.Input) ([]embedding.Value, error) {
	p.calls++
	if p.calls > 1 {
		close(p.started)
		<-p.release
	}
	values := make([]embedding.Value, len(inputs))
	for index, input := range inputs {
		values[index] = embedding.Value{ID: input.ID, Vector: []float32{1, 0}}
	}
	return values, nil
}

func (p *blockingProvider) Close() error { return nil }

func TestSnapshotRemainsReadableWhileSemanticBatchRuns(t *testing.T) {
	root := t.TempDir()
	for name, content := range map[string]string{
		"one.txt": "first lexical fact", "two.txt": "second lexical fact",
	} {
		if err := os.WriteFile(filepath.Join(root, name), []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	provider := &blockingProvider{started: make(chan struct{}), release: make(chan struct{})}
	repository, err := Open(Options{
		Root: root, StorageRoot: t.TempDir(),
		Embedding: &embedding.Capability{
			Provider: provider, ProviderFingerprint: "blocking-v1",
			Model: "test", Dimensions: 2, BatchSize: 1,
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := repository.Refresh(context.Background(), RefreshOptions{EmbeddingBatches: 1}); err != nil {
		t.Fatal(err)
	}
	refreshDone := make(chan error, 1)
	go func() {
		_, err := repository.Refresh(context.Background(), RefreshOptions{EmbeddingBatches: 1})
		refreshDone <- err
	}()
	select {
	case <-provider.started:
	case <-time.After(time.Second):
		t.Fatal("semantic refresh did not reach provider")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()
	snapshot, err := repository.OpenSnapshot(ctx)
	if err != nil {
		close(provider.release)
		t.Fatal(err)
	}
	result, err := query.Run(ctx, snapshot, "lexical fact", query.Options{Mode: "lexical", Limit: 5})
	closeErr := snapshot.Close()
	close(provider.release)
	if refreshErr := <-refreshDone; refreshErr != nil {
		t.Fatal(refreshErr)
	}
	if err != nil || closeErr != nil || len(result.Records) != 2 {
		t.Fatalf("result=%#v err=%v close=%v", result, err, closeErr)
	}
}

func TestRepositoryAgingRefreshIgnoresDemandPriority(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "short.txt"), []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "long.txt"), []byte("deliberately longer content"), 0o644); err != nil {
		t.Fatal(err)
	}
	provider := &retainedProvider{}
	repository, err := Open(Options{
		Root: root, StorageRoot: t.TempDir(),
		Embedding: &embedding.Capability{
			Provider: provider, ProviderFingerprint: "local-test-v1",
			Model: "test", Dimensions: 2, BatchSize: 1,
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	result, err := repository.Refresh(context.Background(), RefreshOptions{
		Aging: true, PriorityPaths: []string{"long.txt"}, EmbeddingBatches: 1,
	})
	if err != nil {
		t.Fatal(err)
	}
	if result.EmbeddingStatus != "partial" || result.Coverage.Embedded != 1 ||
		len(provider.texts) != 1 || !strings.HasSuffix(provider.texts[0], "\nx") {
		t.Fatalf("result=%#v provider=%#v", result, provider)
	}
}
