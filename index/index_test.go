package index

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/coadan/yggdrasil/embedding"
	"github.com/coadan/yggdrasil/extractor"
	"github.com/coadan/yggdrasil/extractor/command"
	"github.com/coadan/yggdrasil/query"
	"github.com/ncruces/go-sqlite3"
)

func TestSnapshotLockErrorsAreFallbackSignals(t *testing.T) {
	for _, err := range []error{
		fmt.Errorf("open snapshot: %w", sqlite3.BUSY),
		fmt.Errorf("read snapshot: %w", sqlite3.LOCKED),
	} {
		if !IsSnapshotUnavailable(err) {
			t.Fatalf("error was not classified as transient: %v", err)
		}
	}
	if IsSnapshotUnavailable(errors.New("database is malformed")) {
		t.Fatal("non-lock storage error must remain visible")
	}
}

func TestRefreshRetriesUnchangedFilesAfterExtractorFailure(t *testing.T) {
	if marker := os.Getenv("YGG_TEST_EXTRACTOR_RETRY"); marker != "" {
		runRetryExtractor(marker)
		return
	}
	root := t.TempDir()
	t.Setenv("YGG_TEST_EXTRACTOR_RETRY", filepath.Join(t.TempDir(), "started"))
	for _, name := range []string{"a.md", "b.md", "untouched.txt"} {
		if err := os.WriteFile(filepath.Join(root, name), []byte("current owner\n"), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	ctx := context.Background()
	provider, err := command.New(ctx, root, command.Spec{
		ID: "retry", Version: "1", IncludeGlobs: []string{"**/*.md"}, TimeoutMS: 2_000,
		Command: []string{os.Args[0], "-test.run=^TestRefreshRetriesUnchangedFilesAfterExtractorFailure$"},
	})
	if err != nil {
		t.Fatal(err)
	}
	defer provider.Close()
	repository, err := Open(Options{Root: root, StorageRoot: t.TempDir(), Extractors: []extractor.Provider{provider}})
	if err != nil {
		t.Fatal(err)
	}
	refresh := func() RefreshResult {
		t.Helper()
		summary, err := repository.Refresh(ctx, RefreshOptions{NoEmbed: true, WaitForWriter: true})
		if err != nil {
			t.Fatal(err)
		}
		return summary
	}
	if first := refresh(); first.Indexed != 3 || first.Diagnostics != 1 {
		t.Fatalf("initial refresh: %#v", first)
	}
	snapshot, err := repository.OpenSnapshot(ctx)
	if err != nil {
		t.Fatal(err)
	}
	lexical, err := query.Run(ctx, snapshot, "current owner", query.Options{Mode: "lexical"})
	snapshot.Close()
	if err != nil || len(lexical.Records) != 3 {
		t.Fatalf("expected current lexical records: %#v, %v", lexical, err)
	}
	if retry := refresh(); retry.Indexed != 2 || retry.Unchanged != 1 || retry.Diagnostics != 1 {
		t.Fatalf("expected only the two matching files to retry, with one parser diagnostic: %#v", retry)
	}
	snapshot, err = repository.OpenSnapshot(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer snapshot.Close()
	extracted, err := snapshot.ExtractorCandidates(ctx, "recovered", "", 10)
	if err != nil || len(extracted) != 1 || extracted[0].Path != "a.md" {
		t.Fatalf("expected recovered plugin record: %#v, %v", extracted, err)
	}
	if complete := refresh(); !complete.UpToDate {
		t.Fatalf("normal parser diagnostics should not keep files pending: %#v", complete)
	}
}

func runRetryExtractor(marker string) {
	scanner := bufio.NewScanner(os.Stdin)
	out := json.NewEncoder(os.Stdout)
	for scanner.Scan() {
		var request struct {
			Type      string
			RequestID string
			File      struct{ Path string }
		}
		if json.Unmarshal(scanner.Bytes(), &request) != nil {
			os.Exit(2)
		}
		switch request.Type {
		case "hello":
			if _, err := os.Stat(marker); os.IsNotExist(err) {
				if os.WriteFile(marker, nil, 0o600) != nil {
					os.Exit(2)
				}
				os.Exit(1)
			}
			_ = out.Encode(map[string]string{"type": "ready", "schema": "ygg.extractor/v1"})
		case "file":
			response := map[string]any{"type": "result", "requestId": request.RequestID}
			if request.File.Path == "a.md" {
				response["records"] = []extractor.Record{{ID: "owner", StartLine: 1, EndLine: 1, Kind: "section", Text: "recovered"}}
			} else {
				response["diagnostics"] = []map[string]string{{"message": "parser diagnostic"}}
			}
			_ = out.Encode(response)
		case "end":
			_ = out.Encode(map[string]string{"type": "summary"})
			return
		}
	}
}

func TestSnapshotKeepsGenerationAcrossRefresh(t *testing.T) {
	ctx := context.Background()
	root := t.TempDir()
	path := filepath.Join(root, "owner.txt")
	write := func(text string) {
		t.Helper()
		if err := os.WriteFile(path, []byte(text), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	write("marker old-generation\n")
	repository, err := Open(Options{Root: root, StorageRoot: t.TempDir()})
	if err != nil {
		t.Fatal(err)
	}
	refresh := func() {
		t.Helper()
		bounded, cancel := context.WithTimeout(ctx, 2*time.Second)
		defer cancel()
		if _, err := repository.Refresh(bounded, RefreshOptions{NoEmbed: true}); err != nil {
			t.Fatal(err)
		}
	}
	refresh()
	snapshot, err := repository.OpenSnapshot(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer snapshot.Close()
	check := func(reader query.Reader, want string) {
		t.Helper()
		result, err := query.Run(ctx, reader, "marker", query.Options{Mode: "lexical", Limit: 5})
		if err != nil || len(result.Records) != 1 || !strings.Contains(result.Records[0].Excerpt, want) {
			t.Fatalf("want %q, result=%#v err=%v", want, result, err)
		}
	}
	check(snapshot, "old-generation")
	write("marker new-generation with an extra fact\n")
	refresh() // An open reader must not prevent the WAL writer from committing.
	check(snapshot, "old-generation")
	current, err := repository.OpenSnapshot(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer current.Close()
	check(current, "new-generation")
}

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
