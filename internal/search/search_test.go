package search

import (
	"context"
	"encoding/json"
	"fmt"
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

func TestSearchReturnsBoundedOverflowPathsWithoutExtraCitations(t *testing.T) {
	ctx := context.Background()
	value, err := store.Open(ctx, filepath.Join(t.TempDir(), "search.sqlite3"), "/repo", "root")
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	for index := range 30 {
		path := fmt.Sprintf("src/owner-%02d.go", index)
		file := discovery.File{
			Candidate: discovery.Candidate{Path: path, Size: 20, MTimeNS: 1},
			Kind:      "go",
		}
		if err := value.ReplaceFile(ctx, "run", file, path, "fingerprint", []contracts.SearchRecord{{
			Path: path, StartLine: 1, EndLine: 1, Kind: "text-chunk",
			Text: "overflow marker", Source: "core",
		}}); err != nil {
			t.Fatal(err)
		}
	}
	result, err := Run(ctx, value, "overflow marker", Options{Mode: "lexical", Limit: 3})
	if err != nil {
		t.Fatal(err)
	}
	if len(result.Records) != 3 || len(result.MorePaths) != maxMorePaths {
		t.Fatalf("records=%d more=%d", len(result.Records), len(result.MorePaths))
	}
	seen := make(map[string]bool)
	for _, record := range result.Records {
		seen[record.Path] = true
	}
	for _, path := range result.MorePaths {
		if seen[path] {
			t.Fatalf("duplicate overflow path %q", path)
		}
		seen[path] = true
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
		{
			path: "scripts/world-map-route.ts",
			records: []contracts.SearchRecord{
				{Path: "scripts/world-map-route.ts", StartLine: 1, EndLine: 1, Kind: "file", Text: "scripts/world-map-route.ts", Source: "core"},
				{Path: "scripts/world-map-route.ts", StartLine: 3, EndLine: 5, Kind: "text-chunk", Text: "world map route", Source: "core"},
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
	result, err := Run(ctx, value, "world map route", Options{
		Mode: "lexical", Limit: 5, Scope: "src/",
	})
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
		strings.Join(result.Records[0].Retrieval, ",") != "literal" {
		t.Fatalf("active result=%#v", result)
	}
}

func TestAutoTreatsJSXTagQueryAsCaseSensitiveLiteral(t *testing.T) {
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
		{"src/import.tsx", "import { DetailEntry } from './DetailEntry';"},
		{"src/prose.md", "DetailEntry is the shared record shell."},
		{
			"src/use.tsx",
			strings.Repeat("const unrelated = true;\n", 8) +
				"<DetailEntry title={item.title}>",
		},
		{"src/wrong-case.tsx", "<detailentry>"},
		{"tests/use.tsx", "<DetailEntry title={fixture.title}>"},
	} {
		file := discovery.File{
			Candidate: discovery.Candidate{Path: item.path, Size: int64(len(item.text)), MTimeNS: 1},
			Kind:      "text",
		}
		if err := value.ReplaceFile(ctx, "run", file, item.path, "fingerprint", []contracts.SearchRecord{{
			Path: item.path, StartLine: 1, EndLine: strings.Count(item.text, "\n") + 1, Kind: "text-chunk",
			Text: item.text, Source: "core",
		}}); err != nil {
			t.Fatal(err)
		}
	}
	result, err := Run(ctx, value, "<DetailEntry", Options{
		Mode: "auto", Limit: 10, Scope: "src/",
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(result.Records) != 1 ||
		result.Records[0].Path != "src/use.tsx" ||
		result.Records[0].StartLine != 7 ||
		!strings.Contains(result.Records[0].Excerpt, "<DetailEntry") ||
		strings.Join(result.Records[0].Retrieval, ",") != "literal" {
		t.Fatalf("literal result=%#v", result)
	}
}

func TestMixedStructuredAnchorsRetainEachOwner(t *testing.T) {
	ctx := context.Background()
	value, err := store.Open(ctx, filepath.Join(t.TempDir(), "search.sqlite3"), "/repo", "root")
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	items := []struct {
		path string
		text string
	}{
		{"src/ContextualAssistancePicker.tsx", "export function ContextualAssistancePicker() {}"},
		{"src/ExplorationStage.tsx", `<details className="stage-time">`},
	}
	for index := range 8 {
		items = append(items, struct {
			path string
			text string
		}{
			path: fmt.Sprintf("catalog/disclosure-disabled-summary-%d.ts", index),
			text: "Disclosure disabled summary",
		})
	}
	for _, item := range items {
		file := discovery.File{
			Candidate: discovery.Candidate{Path: item.path, Size: int64(len(item.text)), MTimeNS: 1},
			Kind:      "typescript",
		}
		if err := value.ReplaceFile(ctx, "run", file, item.path, "fingerprint", []contracts.SearchRecord{
			{
				Path: item.path, StartLine: 1, EndLine: 1, Kind: "file",
				Title: item.path, Text: item.path, Source: "core",
			},
			{
				Path: item.path, StartLine: 1, EndLine: 1, Kind: "text-chunk",
				Text: item.text, Source: "core",
			},
		}); err != nil {
			t.Fatal(err)
		}
	}
	result, err := Run(
		ctx,
		value,
		"ContextualAssistancePicker stage-time Disclosure disabled summary",
		Options{Mode: "lexical", Limit: 5},
	)
	if err != nil {
		t.Fatal(err)
	}
	paths := make(map[string]bool, len(result.Records))
	for _, record := range result.Records {
		paths[record.Path] = true
	}
	for _, expected := range []string{
		"src/ContextualAssistancePicker.tsx",
		"src/ExplorationStage.tsx",
	} {
		if !paths[expected] {
			t.Fatalf("structured owner %q missing from %#v", expected, result.Records)
		}
	}
}

func TestMixedSingleStructuredAnchorPromotesExactOwner(t *testing.T) {
	ctx := context.Background()
	value, err := store.Open(ctx, filepath.Join(t.TempDir(), "search.sqlite3"), "/repo", "root")
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	items := []struct {
		path string
		text string
	}{
		{"src/ui/DetailCallout.tsx", "export function DetailCallout() {}"},
	}
	for index := range 8 {
		items = append(items, struct {
			path string
			text string
		}{
			path: fmt.Sprintf("docs/shared-semantic-rewards-environment-journal-%d.md", index),
			text: "shared semantic rewards environment journal",
		})
	}
	for _, item := range items {
		file := discovery.File{
			Candidate: discovery.Candidate{Path: item.path, Size: int64(len(item.text)), MTimeNS: 1},
			Kind:      "text",
		}
		if err := value.ReplaceFile(ctx, "run", file, item.path, "fingerprint", []contracts.SearchRecord{
			{
				Path: item.path, StartLine: 1, EndLine: 1, Kind: "file",
				Title: item.path, Text: item.path, Source: "core",
			},
			{
				Path: item.path, StartLine: 1, EndLine: 1, Kind: "text-chunk",
				Text: item.text, Source: "core",
			},
		}); err != nil {
			t.Fatal(err)
		}
	}
	result, err := Run(
		ctx,
		value,
		"DetailCallout shared semantic rewards environment journal",
		Options{Mode: "lexical", Limit: 5},
	)
	if err != nil {
		t.Fatal(err)
	}
	if len(result.Records) == 0 ||
		result.Records[0].Path != "src/ui/DetailCallout.tsx" ||
		!strings.Contains(strings.Join(result.Records[0].Retrieval, ","), "anchor") {
		t.Fatalf("exact owner not promoted: %#v", result.Records)
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
		{path: "scripts/outside.go", source: "plugin:go"},
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
		Mode: "lexical", Limit: 5, Scope: "src/", HasExtractors: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	if result.Records[0].Path != "src/extracted.go" ||
		!strings.Contains(strings.Join(result.Records[0].Retrieval, ","), "extractor") {
		t.Fatalf("records=%#v", result.Records)
	}
	for _, record := range result.Records {
		if !strings.HasPrefix(record.Path, "src/") {
			t.Fatalf("out-of-scope extractor record=%#v", record)
		}
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

func TestHybridSearchCitesDenseLexicalEvidenceInsteadOfSemanticFileTail(t *testing.T) {
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
	path := "src/RelationshipInstitutionMandates.tsx"
	file := discovery.File{
		Candidate: discovery.Candidate{Path: path, Size: 200, MTimeNS: 1},
		Kind:      "typescript",
	}
	if err := value.ReplaceFile(ctx, "run", file, "hash", "fingerprint", []contracts.SearchRecord{
		{
			Path: path, StartLine: 1, EndLine: 80, Kind: "text-chunk",
			Title:  path + ":1",
			Text:   "import { DetailSection }\nexport function RelationshipInstitutionMandates() {\n" + strings.Repeat("owner detail\n", 50),
			Source: "core",
		},
		{
			Path: path, StartLine: 141, EndLine: 166, Kind: "text-chunk",
			Title:  path + ":141",
			Text:   "mandates\n" + strings.Repeat("tail action\n", 20) + "</DetailSection>",
			Source: "core",
		},
	}); err != nil {
		t.Fatal(err)
	}
	fingerprint := embedding.Fingerprint(cfg)
	if _, err := value.PrepareEmbeddingLane(ctx, fingerprint, cfg.Model, cfg.Dimensions); err != nil {
		t.Fatal(err)
	}
	inputs, err := value.MissingEmbeddingInputs(ctx, fingerprint, 10)
	if err != nil || len(inputs) != 2 {
		t.Fatalf("inputs=%#v err=%v", inputs, err)
	}
	vectors := make([]store.EmbeddingValue, 0, len(inputs))
	for _, input := range inputs {
		vector := []float32{0, 1}
		if strings.HasPrefix(input.Text, "mandates") {
			vector = []float32{1, 0}
		}
		vectors = append(vectors, store.EmbeddingValue{
			ID: input.ID, InputHash: input.InputHash, Vector: vector,
		})
	}
	if err := value.UpsertEmbeddings(ctx, fingerprint, 2, vectors); err != nil {
		t.Fatal(err)
	}
	result, err := Run(ctx, value, "DetailSection mandates", Options{
		Mode: "auto", Limit: 5, Root: t.TempDir(), Embedding: &cfg,
	})
	if err != nil {
		t.Fatal(err)
	}
	if result.ActiveMode != "hybrid" || len(result.Records) != 1 {
		t.Fatalf("result=%#v", result)
	}
	record := result.Records[0]
	if record.StartLine != 1 || record.Title != path+":1" ||
		!strings.Contains(record.Excerpt, "DetailSection") ||
		!strings.Contains(record.Excerpt, "RelationshipInstitutionMandates") {
		t.Fatalf("citation=%#v", record)
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

func TestMixedQueryRequiresTwoNonIdentifierAnchors(t *testing.T) {
	if got := structuredAnchorQueries("reuse selector-free flow test"); len(got) != 0 {
		t.Fatalf("single natural anchor=%q", got)
	}
	got := structuredAnchorQueries("reuse selector-free exact-content flow test")
	if len(got) != 2 {
		t.Fatalf("multiple structured anchors=%q", got)
	}
}

func TestExcerptPreservesUTF8Boundary(t *testing.T) {
	got := excerpt(strings.Repeat("é", 401))
	if !utf8.ValidString(got) || utf8.RuneCountInString(got) != 281 || !strings.HasSuffix(got, "…") {
		t.Fatalf("invalid excerpt: runes=%d valid=%v suffix=%v", utf8.RuneCountInString(got), utf8.ValidString(got), strings.HasSuffix(got, "…"))
	}
}

func TestCitationEvidenceUsesTokenBoundariesInsteadOfIdentifierSubstrings(t *testing.T) {
	const path = "src/EnvironmentPanel.tsx"
	text := "function installJournalizedConsumerismComponentship() {}\n" +
		strings.Repeat("unrelated props\n", 10) +
		`<DetailCallout data-kind="environment">`
	record := store.Record{
		Path: path, StartLine: 1, EndLine: 12, Kind: "text-chunk",
		Title: path + ":1", Text: text, Source: "core",
	}
	evidence := locateEvidence(
		record.Text,
		"all DetailCallout consumer components in journal and environment",
	)
	startLine, _, title, got := localizedCitation(record, evidence)
	if startLine <= 5 || title == path+":1" || !strings.Contains(got, "DetailCallout") {
		t.Fatalf("start=%d title=%q excerpt=%q evidence=%#v", startLine, title, got, evidence)
	}
}

func TestCitationEvidenceCountsCamelCaseComponentsAsOneQueryTerm(t *testing.T) {
	text := "import { FactList } from './FactList';\n" +
		strings.Repeat("const unrelated = true;\n", 7) +
		"<FactList\n" +
		strings.Repeat("  items={values}\n", 5) +
		"  layout=\"ledger\"\n/>"
	evidence := locateEvidence(text, "FactList ledger layout")
	if evidence.line < 10 || evidence.terms != 2 {
		t.Fatalf("evidence=%#v", evidence)
	}
}
