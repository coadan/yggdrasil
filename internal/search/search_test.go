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

func TestLexicalSearchBoostsExactAndAllTermMatches(t *testing.T) {
	ctx := context.Background()
	value, err := store.Open(ctx, filepath.Join(t.TempDir(), "search.sqlite3"), "/repo", "root")
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	for _, item := range []struct {
		path string
		text string
	}{
		{"src/partial.go", "alpha alpha alpha"},
		{"src/scattered.go", "alpha then beta"},
		{"src/exact.go", "alpha beta"},
	} {
		file := discovery.File{
			Candidate: discovery.Candidate{Path: item.path, Size: int64(len(item.text)), MTimeNS: 1},
			Kind:      "go",
		}
		records := []contracts.SearchRecord{
			{Path: file.Path, StartLine: 1, EndLine: 1, Kind: "file", Title: file.Path, Text: file.Path, Source: "core"},
			{Path: file.Path, StartLine: 10, EndLine: 12, Kind: "text-chunk", Text: item.text, Source: "core"},
		}
		if err := value.ReplaceFile(ctx, "run", file, item.text, "fingerprint", records); err != nil {
			t.Fatal(err)
		}
	}
	result, err := Run(ctx, value, "alpha beta", Options{Mode: "lexical", Limit: 5})
	if err != nil {
		t.Fatal(err)
	}
	if len(result.Records) != 3 {
		t.Fatalf("records=%#v", result.Records)
	}
	if result.Records[0].Path != "src/exact.go" {
		t.Fatalf("first record=%#v", result.Records[0])
	}
	if got := strings.Join(result.Records[0].Retrieval, ","); got != "all-terms,exact,lexical" {
		t.Fatalf("retrieval=%q", got)
	}
	for _, record := range result.Records {
		if record.Kind == "file" {
			t.Fatalf("file record duplicated cited result: %#v", record)
		}
	}
}

func TestLexicalSearchDiversifiesFilesAndCountsPathTerms(t *testing.T) {
	ctx := context.Background()
	value, err := store.Open(ctx, filepath.Join(t.TempDir(), "search.sqlite3"), "/repo", "root")
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	for _, item := range []struct {
		path    string
		records []contracts.SearchRecord
	}{
		{
			path: "src/world-map-route-access.ts",
			records: []contracts.SearchRecord{
				{Path: "src/world-map-route-access.ts", StartLine: 1, EndLine: 1, Kind: "file", Text: "src/world-map-route-access.ts", Source: "core"},
				{Path: "src/world-map-route-access.ts", StartLine: 3, EndLine: 5, Kind: "text-chunk", Text: "route detail", Source: "core"},
				{Path: "src/world-map-route-access.ts", StartLine: 8, EndLine: 10, Kind: "text-chunk", Text: "route state", Source: "core"},
			},
		},
		{
			path: "src/route.ts",
			records: []contracts.SearchRecord{
				{Path: "src/route.ts", StartLine: 1, EndLine: 1, Kind: "file", Text: "src/route.ts", Source: "core"},
				{Path: "src/route.ts", StartLine: 3, EndLine: 5, Kind: "text-chunk", Text: "route detail", Source: "core"},
			},
		},
	} {
		file := discovery.File{
			Candidate: discovery.Candidate{Path: item.path, Size: 20, MTimeNS: 1},
			Kind:      "typescript",
		}
		if err := value.ReplaceFile(ctx, "run", file, item.path, "fingerprint", item.records); err != nil {
			t.Fatal(err)
		}
	}
	result, err := Run(ctx, value, "world map route", Options{Mode: "lexical", Limit: 5})
	if err != nil {
		t.Fatal(err)
	}
	if len(result.Records) != 2 {
		t.Fatalf("records=%#v", result.Records)
	}
	if result.Records[0].Path != "src/world-map-route-access.ts" {
		t.Fatalf("first record=%#v", result.Records[0])
	}
	if got := strings.Join(result.Records[0].Retrieval, ","); !strings.Contains(got, "path") {
		t.Fatalf("retrieval=%q", got)
	}
}

func TestAutoTreatsStructuredTermsAsExactAndReturnsNoPartialPathNoise(t *testing.T) {
	ctx := context.Background()
	value, err := store.Open(ctx, filepath.Join(t.TempDir(), "search.sqlite3"), "/repo", "root")
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	for _, item := range []struct {
		path string
		text string
	}{
		{"catalog/archetype-progression.ts", "archetype progression"},
		{"src/component.css", ".active-progression-card { display: block; }"},
	} {
		file := discovery.File{
			Candidate: discovery.Candidate{Path: item.path, Size: int64(len(item.text)), MTimeNS: 1},
			Kind:      "text",
		}
		if err := value.ReplaceFile(ctx, "run", file, item.path, "fingerprint", []contracts.SearchRecord{{
			Path: item.path, StartLine: 1, EndLine: 1, Kind: "text-chunk",
			Text: item.text, Source: "core",
		}}); err != nil {
			t.Fatal(err)
		}
	}
	cfg := config.Embedding{
		Kind: "openai-compatible", Endpoint: "http://127.0.0.1:1",
		Model: "must-not-run", Dimensions: 2, TimeoutMS: 1,
	}
	result, err := Run(ctx, value, "retired-progression-card", Options{
		Mode: "auto", Limit: 10, Embedding: &cfg,
	})
	if err != nil {
		t.Fatal(err)
	}
	if result.ActiveMode != "lexical" || len(result.Records) != 0 {
		t.Fatalf("retired result=%#v", result)
	}
	result, err = Run(ctx, value, "active-progression-card", Options{
		Mode: "auto", Limit: 10, Embedding: &cfg,
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(result.Records) != 1 || result.Records[0].Path != "src/component.css" ||
		strings.Join(result.Records[0].Retrieval, ",") != "exact" {
		t.Fatalf("active result=%#v", result)
	}
}

func TestLexicalSearchFusesConfiguredExtractorRecords(t *testing.T) {
	ctx := context.Background()
	value, err := store.Open(ctx, filepath.Join(t.TempDir(), "search.sqlite3"), "/repo", "root")
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	for _, item := range []struct {
		path   string
		source string
	}{
		{path: "src/core.go", source: "core"},
		{path: "src/extracted.go", source: "plugin:go"},
	} {
		file := discovery.File{
			Candidate: discovery.Candidate{Path: item.path, Size: 20, MTimeNS: 1},
			Kind:      "go",
		}
		if err := value.ReplaceFile(ctx, "run", file, item.path, "fingerprint", []contracts.SearchRecord{{
			Path: item.path, StartLine: 3, EndLine: 3, Kind: "declaration",
			Text: "route access", Source: item.source,
		}}); err != nil {
			t.Fatal(err)
		}
	}
	result, err := Run(ctx, value, "route access", Options{
		Mode: "lexical", Limit: 5, HasExtractors: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	if result.Records[0].Path != "src/extracted.go" ||
		!strings.Contains(strings.Join(result.Records[0].Retrieval, ","), "extractor") {
		t.Fatalf("records=%#v", result.Records)
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

func TestPromoteThirdRootPreservesTopTwoResults(t *testing.T) {
	values := []*fused{
		{record: store.Record{Path: "docs/first.md"}},
		{record: store.Record{Path: "docs/second.md"}},
		{record: store.Record{Path: "docs/third.md"}},
		{record: store.Record{Path: "src/owner.go"}},
		{record: store.Record{Path: "tests/owner_test.go"}},
	}
	promoteThirdRoot(values)
	got := make([]string, 0, len(values))
	for _, value := range values {
		got = append(got, value.record.Path)
	}
	want := []string{
		"docs/first.md",
		"docs/second.md",
		"src/owner.go",
		"docs/third.md",
		"tests/owner_test.go",
	}
	if strings.Join(got, ",") != strings.Join(want, ",") {
		t.Fatalf("got=%v want=%v", got, want)
	}
}

func TestPathTermsAreMechanicalAndBounded(t *testing.T) {
	got := pathTerms("ONE two three four-five FIVE six seven eight nine ten eleven")
	want := []string{"three", "four", "five", "seven", "eight", "nine"}
	if strings.Join(got, ",") != strings.Join(want, ",") {
		t.Fatalf("path terms=%q want=%q", got, want)
	}
}

func TestQueryTermsSplitStructuredIdentifiers(t *testing.T) {
	got := queryTerms(".retired-progression_card")
	if strings.Join(got, ",") != "retired,progression,card" {
		t.Fatalf("terms=%q", got)
	}
}

func TestExcerptPreservesUTF8Boundary(t *testing.T) {
	got := excerpt(strings.Repeat("é", 401))
	if !utf8.ValidString(got) || utf8.RuneCountInString(got) != 401 || !strings.HasSuffix(got, "…") {
		t.Fatalf("invalid excerpt: runes=%d valid=%v suffix=%v", utf8.RuneCountInString(got), utf8.ValidString(got), strings.HasSuffix(got, "…"))
	}
}
