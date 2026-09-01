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

	embeddingcontract "github.com/coadan/yggdrasil/embedding"
	"github.com/coadan/yggdrasil/internal/config"
	"github.com/coadan/yggdrasil/internal/contracts"
	"github.com/coadan/yggdrasil/internal/discovery"
	"github.com/coadan/yggdrasil/internal/embedding"
	"github.com/coadan/yggdrasil/internal/store"
	querycontract "github.com/coadan/yggdrasil/query"
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

func TestGrepLexicalFormsMatchContentWithoutPathFalsePositives(t *testing.T) {
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
		{"src/owner.go", "func pushCommandErrorEnvelope() {}"},
		{"src/pushCommandErrorEnvelope.go", "func unrelated() {}"},
	} {
		file := discovery.File{
			Candidate: discovery.Candidate{Path: item.path, Size: int64(len(item.text)), MTimeNS: 1},
			Kind:      "go",
		}
		if err := value.ReplaceFile(ctx, "run", file, item.path, "fingerprint", []contracts.SearchRecord{
			{Path: item.path, StartLine: 1, EndLine: 1, Kind: "file", Title: item.path, Text: item.path, Source: "core"},
			{Path: item.path, StartLine: 1, EndLine: 1, Kind: "text-chunk", Text: item.text, Source: "core"},
		}); err != nil {
			t.Fatal(err)
		}
	}
	for _, test := range []struct {
		pattern string
		kind    string
	}{
		{"push", querycontract.MatchFixed},
		{`push.*ErrorEnvelope`, querycontract.MatchRegexp},
	} {
		result, err := Run(ctx, value, test.pattern, Options{
			Mode: "lexical", Limit: 10, MatchKind: test.kind,
		})
		if err != nil {
			t.Fatal(err)
		}
		if len(result.Records) != 1 || result.Records[0].Path != "src/owner.go" {
			t.Fatalf("kind=%s records=%#v", test.kind, result.Records)
		}
	}
}

func TestGraphSearchReturnsAResolvedImportNeighbor(t *testing.T) {
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
			path: "src/owner.ts",
			records: []contracts.SearchRecord{{
				Path: "src/owner.ts", StartLine: 4, EndLine: 8, Kind: "typescript-function",
				Title: "ownRoute", Text: "function ownRoute private topology detail", Source: "plugin:typescript",
			}},
		},
		{
			path: "src/consumer.ts",
			records: []contracts.SearchRecord{{
				Path: "src/consumer.ts", StartLine: 2, EndLine: 2, Kind: "typescript-import",
				Title: "./owner", Text: `import "./owner"`, Source: "plugin:typescript",
			}},
		},
		{
			path: "src/noise.ts",
			records: []contracts.SearchRecord{
				{Path: "src/noise.ts", StartLine: 1, EndLine: 1, Kind: "typescript-function", Title: "noise", Text: "private", Source: "plugin:typescript"},
				{Path: "src/noise.ts", StartLine: 2, EndLine: 2, Kind: "typescript-import", Title: "./a-target", Text: `import "./a-target"`, Source: "plugin:typescript"},
			},
		},
		{
			path: "src/a-target.ts",
			records: []contracts.SearchRecord{{
				Path: "src/a-target.ts", StartLine: 1, EndLine: 1, Kind: "typescript-function",
				Title: "target", Text: "unrelated target", Source: "plugin:typescript",
			}},
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
	if err := value.RebuildGraph(ctx); err != nil {
		t.Fatal(err)
	}
	result, err := Run(ctx, value, "private topology detail", Options{Mode: "graph", Limit: 5, HasExtractors: true})
	if err != nil {
		t.Fatal(err)
	}
	if result.ActiveMode != "graph" || len(result.Records) < 1 || result.Records[0].Path != "src/consumer.ts" {
		t.Fatalf("result=%#v", result)
	}
	if result.Records[0].Kind != "typescript-import" {
		t.Fatalf("citation=%#v", result.Records[0])
	}
	seeded, err := graphLane(ctx, value, "private", []lane{
		{name: "lexical", records: []store.Record{{ID: 100, Path: "src/owner.ts", Text: "private"}}},
		{name: "semantic", records: []store.Record{{ID: 101, Path: "src/noise.ts", Text: "private"}}},
	}, "", 5)
	if err != nil {
		t.Fatal(err)
	}
	if len(seeded.records) == 0 || seeded.records[0].Path != "src/consumer.ts" {
		t.Fatalf("direct-seeded graph=%#v", seeded.records)
	}
	auto, err := Run(ctx, value, "ownRoute", Options{Mode: "auto", Limit: 5, HasExtractors: true})
	if err != nil {
		t.Fatal(err)
	}
	if auto.ActiveMode != "hybrid" || len(auto.Records) < 2 ||
		auto.Records[0].Path != "src/owner.ts" || auto.Records[1].Path != "src/consumer.ts" {
		t.Fatalf("auto result=%#v", auto)
	}
	if got := strings.Join(auto.Records[1].Retrieval, ","); got != "graph" {
		t.Fatalf("neighbor retrieval=%q", got)
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

func TestFusionCombinesDifferentRecordsAfterThePrecisionHead(t *testing.T) {
	ranked := fuse("lifecycle failure", 3, maxExcerptRunes, []lane{
		{
			name: "lexical",
			records: []store.Record{
				{ID: 1, Path: "src/lexical-only.go", StartLine: 1, EndLine: 1, Text: "lifecycle failure"},
				{ID: 2, Path: "src/owner.go", StartLine: 10, EndLine: 10, Text: "lifecycle"},
			},
		},
		{
			name: "semantic",
			records: []store.Record{
				{ID: 3, Path: "src/semantic-only.go", StartLine: 1, EndLine: 1, Text: "failure handling"},
				{ID: 4, Path: "src/owner.go", StartLine: 20, EndLine: 20, Text: "failure"},
			},
		},
	})
	if len(ranked) != 3 ||
		ranked[0].Path != "src/lexical-only.go" ||
		ranked[1].Path != "src/owner.go" {
		t.Fatalf("cross-record path evidence was not fused: %#v", ranked)
	}
	if ranked[1].Score <= ranked[0].Score {
		t.Fatalf("cross-record score=%f head score=%f", ranked[1].Score, ranked[0].Score)
	}
	if got := strings.Join(ranked[1].Retrieval, ","); got != "lexical,semantic" {
		t.Fatalf("retrieval=%q", got)
	}
}

func TestAutoReservesTheLastTopTenSlotForTheStrongestGraphNeighbor(t *testing.T) {
	var lexical []store.Record
	for index := range 40 {
		lexical = append(lexical, store.Record{
			ID: int64(index + 1), Path: fmt.Sprintf("src/direct-%02d.go", index),
			StartLine: 1, EndLine: 1, Kind: "text-chunk", Text: "direct evidence",
		})
	}
	graph := store.Record{
		ID: 100, Path: "src/linked-owner.go",
		StartLine: 1, EndLine: 1, Kind: "import", Text: "linked owner",
	}
	result := Result{RequestedMode: "auto", ActiveMode: "hybrid"}
	setResultRecords(&result, "direct evidence", 10, []lane{
		{name: "lexical", records: lexical},
		{name: "graph", records: []store.Record{graph}},
	})
	if len(result.Records) != 10 || result.Records[0].Path != "src/direct-00.go" ||
		result.Records[9].Path != graph.Path || len(result.MorePaths) != maxMorePaths ||
		result.MorePaths[maxMorePaths-1] != "src/direct-09.go" {
		t.Fatalf("records=%#v", result.Records)
	}
}

func TestFusionBalancesRetrieverFamiliesInsteadOfLaneCount(t *testing.T) {
	lexicalLanes := []lane{
		{name: "exact"},
		{name: "all-terms"},
		{name: "lexical"},
		{name: "path"},
	}
	for laneIndex := range lexicalLanes {
		for index := range 20 {
			lexicalLanes[laneIndex].records = append(
				lexicalLanes[laneIndex].records,
				store.Record{
					ID:        int64(1000*laneIndex + index + 1),
					Path:      fmt.Sprintf("noise/%02d.go", index),
					StartLine: 1, EndLine: 1, Kind: "text-chunk", Text: "broad lexical noise",
				},
			)
		}
	}
	lanes := append(lexicalLanes, lane{
		name: "semantic",
		records: []store.Record{{
			ID: 99999, Path: "src/semantic-owner.go",
			StartLine: 1, EndLine: 1, Kind: "text-chunk", Text: "semantic owner",
		}},
	})
	ranked := fuse("semantic owner", 10, maxExcerptRunes, lanes)
	ownerRank := -1
	for index, record := range ranked {
		if record.Path == "src/semantic-owner.go" {
			ownerRank = index + 1
			break
		}
	}
	if ownerRank != 2 {
		t.Fatalf("semantic owner rank=%d records=%#v", ownerRank, ranked)
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

func TestAutoTreatsSyntaxQueryAsCaseSensitiveLiteral(t *testing.T) {
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
		{"src/call-import.ts", "import { runTask } from './task';"},
		{"src/call.ts", "const result = runTask(value);"},
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
	result, err = Run(ctx, value, "runTask(", Options{
		Mode: "auto", Limit: 10, Scope: "src/",
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(result.Records) != 1 ||
		result.Records[0].Path != "src/call.ts" ||
		strings.Join(result.Records[0].Retrieval, ",") != "literal" {
		t.Fatalf("call result=%#v", result)
	}
}

func TestAutoTreatsBareCodeIdentifierAsCaseSensitiveLiteral(t *testing.T) {
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
		{"src/ContentStack.tsx", "export function ContentStack() {}"},
		{"src/use.tsx", "<ContentStack title={title} />"},
		{"src/partial.ts", "export function ContentStacked() {}"},
		{"src/compound.ts", "export function ProviderContentStack() {}"},
		{"src/dollar.ts", "export function $ContentStack() {}"},
		{"docs/concept.md", "A content stack arranges related copy."},
	} {
		file := discovery.File{
			Candidate: discovery.Candidate{
				Path: item.path, Size: int64(len(item.text)), MTimeNS: 1,
			},
			Kind: "text",
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
	result, err := Run(ctx, value, "ContentStack", Options{
		Mode: "auto", Limit: 10, Embedding: &cfg,
	})
	if err != nil {
		t.Fatal(err)
	}
	if result.ActiveMode != "lexical" || len(result.Records) != 2 {
		t.Fatalf("identifier result=%#v", result)
	}
	for _, record := range result.Records {
		if !strings.Contains(record.Excerpt, "ContentStack") ||
			strings.Join(record.Retrieval, ",") != "literal" {
			t.Fatalf("identifier record=%#v", record)
		}
	}
}

func TestContainsIdentifierLiteral(t *testing.T) {
	for text, want := range map[string]bool{
		"Metric":                    true,
		"<Metric value={total} />":  true,
		"const value = Metric(foo)": true,
		"Metrics":                   false,
		"ProviderMetric":            false,
		"MetricValue":               false,
		"$Metric":                   false,
		"_Metric":                   false,
	} {
		if got := containsIdentifierLiteral(text, "Metric"); got != want {
			t.Fatalf("text=%q got=%t want=%t", text, got, want)
		}
	}
}

func TestLiteralTermQueryRecognizesCaseMarkedIdentifiers(t *testing.T) {
	for query, want := range map[string]bool{
		"ContentStack": true,
		"Metric":       true,
		"runTask":      true,
		"API":          true,
		"database":     false,
		"x":            false,
		"two words":    false,
	} {
		if got := literalTermQuery(query, queryTerms(query)); got != want {
			t.Fatalf("query=%q got=%t want=%t", query, got, want)
		}
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

type retainedQueryProvider struct {
	calls      int
	closeCalls int
}

func (p *retainedQueryProvider) Embed(_ context.Context, inputs []embeddingcontract.Input) ([]embeddingcontract.Value, error) {
	p.calls++
	return []embeddingcontract.Value{{ID: inputs[0].ID, Vector: []float32{1, 0}}}, nil
}

func (p *retainedQueryProvider) Close() error {
	p.closeCalls++
	return nil
}

func TestRunUsesCallerOwnedEmbeddingProvider(t *testing.T) {
	cfg := config.Embedding{
		Kind: "command", Command: []string{"must-not-start"}, Model: "retained",
		Dimensions: 2, TimeoutMS: 1_000, QueryPrefix: "query: ",
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
		Path: file.Path, StartLine: 1, EndLine: 1, Kind: "text-chunk",
		Text: "semantic target", Source: "core",
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
	if _, err := value.UpsertEmbeddings(ctx, fingerprint, 2, []store.EmbeddingValue{{
		ID: inputs[0].ID, InputHash: inputs[0].InputHash, Vector: []float32{1, 0},
	}}); err != nil {
		t.Fatal(err)
	}
	provider := &retainedQueryProvider{}
	result, err := Run(ctx, value, "semantic target", Options{
		Mode: "auto", Limit: 5, Embedding: &cfg, EmbeddingProvider: provider,
	})
	if err != nil {
		t.Fatal(err)
	}
	if result.ActiveMode != "hybrid" || provider.calls != 1 || provider.closeCalls != 0 {
		t.Fatalf("result=%#v provider=%#v", result, provider)
	}
}

func TestAutoActivatesPartialSemanticCoverageWithoutDisplacingConcreteEvidence(t *testing.T) {
	cfg := config.Embedding{
		Kind: "command", Command: []string{"must-not-start"}, Model: "retained",
		Dimensions: 2, TimeoutMS: 1_000, QueryPrefix: "query: ",
	}
	ctx := context.Background()
	value, err := store.Open(ctx, filepath.Join(t.TempDir(), "search.sqlite3"), "/repo", "root")
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	for _, item := range []struct{ path, text string }{
		{"src/concrete.go", "needle concrete evidence"},
		{"src/semantic.go", "conceptually related"},
	} {
		file := discovery.File{
			Candidate: discovery.Candidate{Path: item.path, Size: int64(len(item.text)), MTimeNS: 1},
			Kind:      "go",
		}
		if err := value.ReplaceFile(ctx, "run", file, item.text, "fingerprint", []contracts.SearchRecord{{
			Path: item.path, StartLine: 1, EndLine: 1, Kind: "text-chunk", Text: item.text, Source: "core",
		}}); err != nil {
			t.Fatal(err)
		}
	}
	fingerprint := embedding.Fingerprint(cfg)
	if _, err := value.PrepareEmbeddingLane(ctx, fingerprint, cfg.Model, cfg.Dimensions); err != nil {
		t.Fatal(err)
	}
	inputs, err := value.MissingEmbeddingInputs(ctx, fingerprint, 10)
	if err != nil {
		t.Fatal(err)
	}
	var semantic store.EmbeddingInput
	for _, input := range inputs {
		if input.Text == "conceptually related" {
			semantic = input
		}
	}
	if semantic.ID == 0 {
		t.Fatalf("inputs=%#v", inputs)
	}
	if _, err := value.UpsertEmbeddings(ctx, fingerprint, 2, []store.EmbeddingValue{{
		ID: semantic.ID, InputHash: semantic.InputHash, Vector: []float32{1, 0},
	}}); err != nil {
		t.Fatal(err)
	}
	provider := &retainedQueryProvider{}
	below, err := Run(ctx, value, "needle", Options{
		Mode: "auto", Limit: 1, Scope: "src/", Embedding: &cfg,
		EmbeddingProvider: provider, MinSemanticCoverage: 0.75,
	})
	if err != nil {
		t.Fatal(err)
	}
	if provider.calls != 0 || below.FallbackReason != "semantic-below-threshold" ||
		below.Semantic == nil || below.Semantic.Coverage != 0.5 {
		t.Fatalf("below=%#v provider=%#v", below, provider)
	}
	active, err := Run(ctx, value, "needle", Options{
		Mode: "auto", Limit: 1, Scope: "src/", Embedding: &cfg,
		EmbeddingProvider: provider, MinSemanticCoverage: 0.5,
	})
	if err != nil {
		t.Fatal(err)
	}
	if provider.calls != 1 || active.ActiveMode != "lexical" ||
		active.FallbackReason != "semantic-partial" || active.Semantic == nil ||
		active.Semantic.State != "active-partial" || len(active.Records) != 1 ||
		active.Records[0].Path != "src/concrete.go" ||
		len(active.MorePaths) != 1 || active.MorePaths[0] != "src/semantic.go" {
		t.Fatalf("active=%#v provider=%#v", active, provider)
	}
}

func TestAutoFusesRegexpAndExplicitSemanticIntent(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		var body struct {
			Input []string `json:"input"`
		}
		if err := json.NewDecoder(request.Body).Decode(&body); err != nil {
			t.Error(err)
			return
		}
		if len(body.Input) != 1 || body.Input[0] != "query: API command error envelope" {
			t.Errorf("query embedding input=%q", body.Input)
		}
		_ = json.NewEncoder(writer).Encode(map[string]any{
			"data": []map[string]any{{"index": 0, "embedding": []float32{1, 0}}},
		})
	}))
	defer server.Close()
	cfg := config.Embedding{
		Kind: "openai-compatible", Endpoint: server.URL, Model: "test",
		Dimensions: 2, TimeoutMS: 1_000, QueryPrefix: "query: ",
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
	if _, err := value.UpsertEmbeddings(ctx, fingerprint, 2, []store.EmbeddingValue{{
		ID: inputs[0].ID, InputHash: inputs[0].InputHash, Vector: []float32{1, 0},
	}}); err != nil {
		t.Fatal(err)
	}
	result, err := Run(ctx, value, `push.*ErrorEnvelope`, Options{
		Mode: "auto", Limit: 5, Root: t.TempDir(), Embedding: &cfg,
		MatchKind: querycontract.MatchRegexp, About: "API command error envelope",
	})
	if err != nil {
		t.Fatal(err)
	}
	if result.ActiveMode != "hybrid" || len(result.Records) != 1 ||
		result.QueryPlan.Semantic == nil || result.QueryPlan.Semantic.Source != "about" {
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
	if _, err := value.UpsertEmbeddings(ctx, fingerprint, 2, vectors); err != nil {
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
	got := excerpt(strings.Repeat("é", 401), maxExcerptRunes)
	if !utf8.ValidString(got) || utf8.RuneCountInString(got) != 281 || !strings.HasSuffix(got, "…") {
		t.Fatalf("invalid excerpt: runes=%d valid=%v suffix=%v", utf8.RuneCountInString(got), utf8.ValidString(got), strings.HasSuffix(got, "…"))
	}
}

func TestResultExcerptLimitKeepsCommonResponsesBounded(t *testing.T) {
	for limit, want := range map[int]int{
		1: 280, 10: 240, 15: 160, 20: 120, 100: 120,
	} {
		if got := resultExcerptLimit(limit); got != want {
			t.Fatalf("limit=%d got=%d want=%d", limit, got, want)
		}
	}
}

func TestRankedRecordJSONOmitsFusionDiagnostics(t *testing.T) {
	raw, err := json.Marshal(RankedRecord{
		Path: "src/router.go", StartLine: 4, EndLine: 8, Kind: "text-chunk",
		Title: "src/router.go:4", Excerpt: "func routeRequest() {}", Source: "core",
		Retrieval: []string{"lexical", "semantic"}, Score: 0.42,
	})
	if err != nil {
		t.Fatal(err)
	}
	encoded := string(raw)
	for _, field := range []string{`"retrieval"`, `"score"`, `"kind"`, `"title"`, `"source"`} {
		if strings.Contains(encoded, field) {
			t.Fatalf("default public record exposes redundant %s: %s", field, encoded)
		}
	}
	for _, field := range []string{`"path"`, `"startLine"`, `"endLine"`, `"excerpt"`} {
		if !strings.Contains(encoded, field) {
			t.Fatalf("public search record lacks %s: %s", field, encoded)
		}
	}
}

func TestRankedRecordJSONRetainsExtractorEvidence(t *testing.T) {
	raw, err := json.Marshal(RankedRecord{
		Path: "src/router.go", StartLine: 4, EndLine: 8, Kind: "function",
		Title: "routeRequest", Excerpt: "func routeRequest() {}", Source: "plugin:go",
	})
	if err != nil {
		t.Fatal(err)
	}
	encoded := string(raw)
	for _, field := range []string{
		`"kind":"function"`, `"title":"routeRequest"`, `"source":"plugin:go"`,
	} {
		if !strings.Contains(encoded, field) {
			t.Fatalf("extractor record lacks %s: %s", field, encoded)
		}
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
	startLine, _, title, got := localizedCitation(record, evidence, maxExcerptRunes)
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
