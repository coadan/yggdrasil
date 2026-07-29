package status

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"github.com/coadan/yggdrasil/internal/config"
	"github.com/coadan/yggdrasil/internal/indexer"
	"github.com/coadan/yggdrasil/internal/project"
)

func TestInspectDoesNotReportDeliberatelySkippedFilesAsDrift(t *testing.T) {
	root := t.TempDir()
	t.Setenv("YGG_STORAGE_ROOT", t.TempDir())
	if err := os.WriteFile(filepath.Join(root, "text.txt"), []byte("searchable"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "binary.dat"), []byte{0, 1, 2}, 0o644); err != nil {
		t.Fatal(err)
	}
	paths, err := project.Resolve(root)
	if err != nil {
		t.Fatal(err)
	}
	cfg := config.Default()
	summary, err := indexer.Run(context.Background(), paths, cfg, indexer.Options{})
	if err != nil || summary.Indexed != 1 || summary.Skipped != 1 {
		t.Fatalf("summary=%#v err=%v", summary, err)
	}
	result, err := Inspect(context.Background(), paths, cfg, Options{})
	if err != nil {
		t.Fatal(err)
	}
	if result.Freshness.New != 0 ||
		result.Freshness.Modified != 0 ||
		result.Freshness.Deleted != 0 ||
		result.Freshness.Unchanged != 1 {
		t.Fatalf("freshness=%#v", result.Freshness)
	}
}

func TestInspectExplainsConfigurationFamilyAndProviderReadiness(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		_ = json.NewEncoder(writer).Encode(map[string]any{
			"data": []map[string]any{{
				"index": 0, "embedding": []float32{1, 0},
			}},
		})
	}))
	defer server.Close()
	root := t.TempDir()
	t.Setenv("YGG_STORAGE_ROOT", t.TempDir())
	if err := os.WriteFile(filepath.Join(root, "text.txt"), []byte("searchable"), 0o644); err != nil {
		t.Fatal(err)
	}
	paths, err := project.Resolve(root)
	if err != nil {
		t.Fatal(err)
	}
	cfg := config.Default()
	cfg.UserConfigPath = "/config/ygg/config.json"
	cfg.UserConfigLoaded = true
	cfg.EmbeddingSource = cfg.UserConfigPath
	cfg.Embedding = &config.Embedding{
		Kind: "openai-compatible", Endpoint: server.URL,
		Model: "fixture", Dimensions: 2, TimeoutMS: 1_000,
	}
	result, err := Inspect(context.Background(), paths, cfg, Options{
		Version: "test-version", CheckProvider: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	if result.Version != "test-version" || result.Configuration.EmbeddingSource != cfg.UserConfigPath ||
		result.GitFamily.ID == "" || result.GitFamily.AvailableSeeds != 0 ||
		result.EmbeddingProvider == nil || !result.EmbeddingProvider.Available ||
		!result.EmbeddingProvider.Checked || result.Freshness.New != 1 {
		t.Fatalf("result=%#v", result)
	}
}
