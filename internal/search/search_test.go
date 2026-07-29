package search

import (
	"context"
	"path/filepath"
	"testing"

	"github.com/coadan/yggdrasil/internal/contracts"
	"github.com/coadan/yggdrasil/internal/discovery"
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
