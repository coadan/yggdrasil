package indexer

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/coadan/yggdrasil/internal/config"
	"github.com/coadan/yggdrasil/internal/contracts"
	"github.com/coadan/yggdrasil/internal/discovery"
	"github.com/coadan/yggdrasil/internal/embedding"
	"github.com/coadan/yggdrasil/internal/project"
	"github.com/coadan/yggdrasil/internal/search"
	"github.com/coadan/yggdrasil/internal/store"
)

func TestRunIsIncrementalAndDeletesMissingFiles(t *testing.T) {
	root := t.TempDir()
	storage := t.TempDir()
	t.Setenv("YGG_STORAGE_ROOT", storage)
	file := filepath.Join(root, "a.txt")
	if err := os.WriteFile(file, []byte("hello"), 0o644); err != nil {
		t.Fatal(err)
	}
	paths, err := project.Resolve(root)
	if err != nil {
		t.Fatal(err)
	}
	first, err := Run(context.Background(), paths, config.Default(), Options{})
	if err != nil || first.Indexed != 1 {
		t.Fatalf("first=%#v err=%v", first, err)
	}
	second, err := Run(context.Background(), paths, config.Default(), Options{})
	if err != nil || second.Unchanged != 1 || second.Indexed != 0 {
		t.Fatalf("second=%#v err=%v", second, err)
	}
	time.Sleep(time.Millisecond)
	if err := os.WriteFile(file, []byte("changed"), 0o644); err != nil {
		t.Fatal(err)
	}
	third, err := Run(context.Background(), paths, config.Default(), Options{})
	if err != nil || third.Indexed != 1 {
		t.Fatalf("third=%#v err=%v", third, err)
	}
	if err := os.Remove(file); err != nil {
		t.Fatal(err)
	}
	fourth, err := Run(context.Background(), paths, config.Default(), Options{})
	if err != nil || fourth.Deleted != 1 {
		t.Fatalf("fourth=%#v err=%v", fourth, err)
	}
	value, err := store.Open(context.Background(), paths.Database, paths.Root, paths.ID)
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	counts, err := value.Counts(context.Background())
	if err != nil || counts.Files != 0 {
		t.Fatalf("counts=%#v err=%v", counts, err)
	}
}

func TestRunReplacesPreRedactionStateAndDeletesNewlySkippedSecrets(t *testing.T) {
	root := t.TempDir()
	t.Setenv("YGG_STORAGE_ROOT", t.TempDir())
	fixtures := map[string]string{
		".env":        "API_TOKEN=ultraconfidentialvalue\n",
		"private.pem": "retiredsecretvalue\n",
	}
	for name, content := range fixtures {
		if err := os.WriteFile(filepath.Join(root, name), []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	paths, err := project.Resolve(root)
	if err != nil {
		t.Fatal(err)
	}
	value, err := store.Open(context.Background(), paths.Database, paths.Root, paths.ID)
	if err != nil {
		t.Fatal(err)
	}
	for name, content := range fixtures {
		info, err := os.Stat(filepath.Join(root, name))
		if err != nil {
			t.Fatal(err)
		}
		file := discovery.File{
			Candidate: discovery.Candidate{
				Path: name, Size: info.Size(), MTimeNS: info.ModTime().UnixNano(),
			},
			Kind: "text", Content: content,
		}
		if err := value.ReplaceFile(context.Background(), "old", file, "old-hash", "v1", []contracts.SearchRecord{{
			Path: name, StartLine: 1, EndLine: 1, Kind: "text-chunk",
			Text: content, Source: "core",
		}}); err != nil {
			t.Fatal(err)
		}
	}
	if err := value.Close(); err != nil {
		t.Fatal(err)
	}

	summary, err := Run(context.Background(), paths, config.Default(), Options{})
	if err != nil {
		t.Fatal(err)
	}
	if summary.Indexed != 1 || summary.Skipped != 1 || summary.Deleted != 1 {
		t.Fatalf("summary=%#v", summary)
	}
	value, err = store.Open(context.Background(), paths.Database, paths.Root, paths.ID)
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	for _, query := range []string{"ultraconfidentialvalue", "retiredsecretvalue"} {
		result, err := search.Run(context.Background(), value, query, search.Options{Mode: "lexical", Limit: 10})
		if err != nil {
			t.Fatal(err)
		}
		if len(result.Records) != 0 {
			t.Fatalf("%s leaked: %#v", query, result.Records)
		}
	}
	result, err := search.Run(context.Background(), value, "API_TOKEN", search.Options{Mode: "lexical", Limit: 10})
	if err != nil || len(result.Records) == 0 || result.Records[0].Path != ".env" {
		t.Fatalf("key result=%#v err=%v", result, err)
	}
}

func TestRunCommitsMultipleBoundedWriteBatches(t *testing.T) {
	root := t.TempDir()
	t.Setenv("YGG_STORAGE_ROOT", t.TempDir())
	const fileCount = writeBatchSize*2 + 1
	for i := range fileCount {
		path := filepath.Join(root, fmt.Sprintf("%03d.txt", i))
		if err := os.WriteFile(path, []byte(fmt.Sprintf("record %d", i)), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	paths, err := project.Resolve(root)
	if err != nil {
		t.Fatal(err)
	}
	first, err := Run(context.Background(), paths, config.Default(), Options{})
	if err != nil || first.Indexed != fileCount {
		t.Fatalf("first=%#v err=%v", first, err)
	}
	second, err := Run(context.Background(), paths, config.Default(), Options{})
	if err != nil || second.Indexed != 0 || second.Unchanged != fileCount {
		t.Fatalf("second=%#v err=%v", second, err)
	}
	value, err := store.Open(context.Background(), paths.Database, paths.Root, paths.ID)
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	counts, err := value.Counts(context.Background())
	if err != nil || counts.Files != fileCount || counts.Records != fileCount*2 {
		t.Fatalf("counts=%#v err=%v", counts, err)
	}
}

func TestRunAddsConfiguredPluginRecords(t *testing.T) {
	if os.Getenv("YGG_INDEXER_PLUGIN_HELPER") == "1" {
		runIndexerPluginHelper()
		return
	}
	root := t.TempDir()
	t.Setenv("YGG_STORAGE_ROOT", t.TempDir())
	t.Setenv("YGG_INDEXER_PLUGIN_HELPER", "1")
	if err := os.WriteFile(filepath.Join(root, "README.md"), []byte("# Heading\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	paths, err := project.Resolve(root)
	if err != nil {
		t.Fatal(err)
	}
	cfg := config.Default()
	cfg.Plugins = []config.Plugin{{
		ID: "helper", Version: "1",
		Command:      []string{os.Args[0], "-test.run=TestRunAddsConfiguredPluginRecords"},
		IncludeGlobs: []string{"**/*.md"}, TimeoutMS: 1_000,
	}}
	if _, err := Run(context.Background(), paths, cfg, Options{}); err != nil {
		t.Fatal(err)
	}
	value, err := store.Open(context.Background(), paths.Database, paths.Root, paths.ID)
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	result, err := search.Run(context.Background(), value, "pluginunique", search.Options{Mode: "lexical"})
	if err != nil {
		t.Fatal(err)
	}
	if len(result.Records) == 0 || result.Records[0].Source != "plugin:helper" {
		t.Fatalf("plugin record missing: %#v", result.Records)
	}
}

func TestRunBuildsConfiguredEmbeddingLane(t *testing.T) {
	requests := 0
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		requests++
		var body struct {
			Input []string `json:"input"`
		}
		if err := json.NewDecoder(request.Body).Decode(&body); err != nil {
			t.Error(err)
			return
		}
		data := make([]map[string]any, len(body.Input))
		for i := range body.Input {
			data[i] = map[string]any{"index": i, "embedding": []float32{1, 0}}
		}
		_ = json.NewEncoder(writer).Encode(map[string]any{"data": data})
	}))
	defer server.Close()
	root := t.TempDir()
	t.Setenv("YGG_STORAGE_ROOT", t.TempDir())
	if err := os.WriteFile(filepath.Join(root, "a.txt"), []byte("hello"), 0o644); err != nil {
		t.Fatal(err)
	}
	paths, err := project.Resolve(root)
	if err != nil {
		t.Fatal(err)
	}
	cfg := config.Default()
	cfg.Embedding = &config.Embedding{
		Kind: "openai-compatible", Endpoint: server.URL, Model: "test",
		Dimensions: 2, TimeoutMS: 1_000, BatchSize: 8,
	}
	summary, err := Run(context.Background(), paths, cfg, Options{})
	if err != nil {
		t.Fatal(err)
	}
	if summary.Embedded == 0 || summary.EmbeddingStatus != "ready" {
		t.Fatalf("summary=%#v", summary)
	}
	value, err := store.Open(context.Background(), paths.Database, paths.Root, paths.ID)
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	state, err := value.EmbeddingState(context.Background(), embedding.Fingerprint(*cfg.Embedding))
	if err != nil || !state.Complete || state.Embedded == 0 {
		t.Fatalf("state=%#v err=%v", state, err)
	}
	if requests == 0 {
		t.Fatal("embedding provider was not called")
	}
	firstRequests := requests
	second, err := Run(context.Background(), paths, cfg, Options{})
	if err != nil || second.Embedded != 0 || second.EmbeddingStatus != "ready" {
		t.Fatalf("second=%#v err=%v", second, err)
	}
	if requests != firstRequests {
		t.Fatalf("no-op index called provider: requests %d -> %d", firstRequests, requests)
	}
}

func runIndexerPluginHelper() {
	scanner := bufio.NewScanner(os.Stdin)
	encoder := json.NewEncoder(os.Stdout)
	for scanner.Scan() {
		var message map[string]any
		if err := json.Unmarshal(scanner.Bytes(), &message); err != nil {
			os.Exit(2)
		}
		switch message["type"] {
		case "hello":
			_ = encoder.Encode(map[string]any{"type": "ready", "schema": "ygg.extractor/v1"})
		case "file":
			_ = encoder.Encode(map[string]any{
				"type": "result", "requestId": message["requestId"],
				"records": []map[string]any{{
					"id": "record", "startLine": 1, "endLine": 1,
					"kind": "test", "text": "pluginunique",
				}},
			})
		case "end":
			_ = encoder.Encode(map[string]any{"type": "summary", "files": 1, "records": 1})
			return
		}
	}
}
