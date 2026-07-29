package search

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"unicode/utf8"

	"github.com/coadan/yggdrasil/internal/config"
	"github.com/coadan/yggdrasil/internal/contracts"
	"github.com/coadan/yggdrasil/internal/discovery"
	"github.com/coadan/yggdrasil/internal/embedding"
	"github.com/coadan/yggdrasil/internal/store"
)

func TestLexicalSearchReturnsCitedRecords(t *testing.T) {
	ctx := context.Background()
	value, err := store.Open(ctx, filepath.Join(t.TempDir(), "search.sqlite3"), "/repo", "root")
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	file := discovery.File{
		Candidate: discovery.Candidate{Path: "src/router.go", Size: 20, MTimeNS: 1},
		Kind:      "go",
	}
	records := []contracts.SearchRecord{
		{Path: file.Path, StartLine: 1, EndLine: 1, Kind: "file", Title: file.Path, Text: file.Path, Source: "core"},
		{Path: file.Path, StartLine: 10, EndLine: 12, Kind: "text-chunk", Title: "route", Text: "register request router", Source: "core"},
	}
	if err := value.ReplaceFile(ctx, "run", file, "hash", "fingerprint", records); err != nil {
		t.Fatal(err)
	}
	result, err := Run(ctx, value, "request router", Options{Mode: "lexical", Limit: 5})
	if err != nil {
		t.Fatal(err)
	}
	if len(result.Records) == 0 || result.Records[0].Path != file.Path {
		t.Fatalf("unexpected records: %#v", result.Records)
	}
	if result.Records[0].StartLine == 0 || result.Records[0].Excerpt == "" {
		t.Fatalf("missing citation: %#v", result.Records[0])
	}
}

func TestAutoReportsSemanticFallback(t *testing.T) {
	ctx := context.Background()
	value, err := store.Open(ctx, filepath.Join(t.TempDir(), "search.sqlite3"), "/repo", "root")
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	result, err := Run(ctx, value, "nothing", Options{Mode: "auto"})
	if err != nil {
		t.Fatal(err)
	}
	if result.ActiveMode != "lexical" || result.FallbackReason != "semantic-unconfigured" {
		t.Fatalf("unexpected result: %#v", result)
	}
}

func TestAutoFusesConfiguredSemanticLane(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		_ = json.NewEncoder(writer).Encode(map[string]any{
			"data": []map[string]any{{"index": 0, "embedding": []float32{1, 0}}},
		})
	}))
	defer server.Close()
	cfg := config.Embedding{
		Kind: "openai-compatible", Endpoint: server.URL, Model: "test",
		Dimensions: 2, TimeoutMS: 1_000,
	}
	ctx := context.Background()
	value, err := store.Open(ctx, filepath.Join(t.TempDir(), "search.sqlite3"), "/repo", "root")
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	file := discovery.File{
		Candidate: discovery.Candidate{Path: "src/target.go", Size: 20, MTimeNS: 1},
		Kind:      "go",
	}
	if err := value.ReplaceFile(ctx, "run", file, "hash", "fingerprint", []contracts.SearchRecord{{
		Path: file.Path, StartLine: 4, EndLine: 6, Kind: "text-chunk",
		Text: "mechanically unrelated words", Source: "core",
	}}); err != nil {
		t.Fatal(err)
	}
	fingerprint := embedding.Fingerprint(cfg)
	if _, err := value.PrepareEmbeddingLane(ctx, fingerprint, cfg.Model, cfg.Dimensions); err != nil {
		t.Fatal(err)
	}
	inputs, err := value.MissingEmbeddingInputs(ctx, fingerprint, 10)
	if err != nil || len(inputs) != 1 {
		t.Fatalf("inputs=%#v err=%v", inputs, err)
	}
	if err := value.UpsertEmbeddings(ctx, fingerprint, 2, []store.EmbeddingValue{{
		ID: inputs[0].ID, InputHash: inputs[0].InputHash, Vector: []float32{1, 0},
	}}); err != nil {
		t.Fatal(err)
	}
	result, err := Run(ctx, value, "conceptual query", Options{
		Mode: "auto", Limit: 5, Root: t.TempDir(), Embedding: &cfg,
	})
	if err != nil {
		t.Fatal(err)
	}
	if result.ActiveMode != "hybrid" || len(result.Records) != 1 {
		t.Fatalf("result=%#v", result)
	}
	if result.Records[0].Path != file.Path || result.Records[0].Retrieval[0] != "semantic" {
		t.Fatalf("records=%#v", result.Records)
	}
}

func TestSearchRejectsUnboundedInputs(t *testing.T) {
	ctx := context.Background()
	if _, err := Run(ctx, nil, "query", Options{Limit: MaxResults + 1}); err == nil {
		t.Fatal("expected result limit error")
	}
	if _, err := Run(ctx, nil, strings.Repeat("x", MaxQueryBytes+1), Options{}); err == nil {
		t.Fatal("expected query byte limit error")
	}
	if _, err := Run(ctx, nil, strings.Repeat("word ", MaxQueryTerms+1), Options{}); err == nil {
		t.Fatal("expected query term limit error")
	}
}

func TestExcerptPreservesUTF8Boundary(t *testing.T) {
	got := excerpt(strings.Repeat("é", 401))
	if !utf8.ValidString(got) || utf8.RuneCountInString(got) != 401 || !strings.HasSuffix(got, "…") {
		t.Fatalf("invalid excerpt: runes=%d valid=%v suffix=%v", utf8.RuneCountInString(got), utf8.ValidString(got), strings.HasSuffix(got, "…"))
	}
}
