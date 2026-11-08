package query

import (
	"context"
	"errors"
	"fmt"
	"path/filepath"
	"strings"
	"testing"

	"github.com/coadan/yggdrasil/internal/contracts"
	"github.com/coadan/yggdrasil/internal/discovery"
	"github.com/coadan/yggdrasil/internal/store"
	rerankcontract "github.com/coadan/yggdrasil/rerank"
)

type fakeReranker struct {
	calls  int
	query  string
	inputs []rerankcontract.Input
	score  func(rerankcontract.Input) float64
	err    error
}

func (f *fakeReranker) Rerank(
	_ context.Context,
	query string,
	inputs []rerankcontract.Input,
) ([]rerankcontract.Score, error) {
	f.calls++
	f.query = query
	f.inputs = append([]rerankcontract.Input(nil), inputs...)
	if f.err != nil {
		return nil, f.err
	}
	scores := make([]rerankcontract.Score, len(inputs))
	for index, input := range inputs {
		scores[index] = rerankcontract.Score{ID: input.ID, Score: f.score(input)}
	}
	return scores, nil
}

func (f *fakeReranker) Close() error { return nil }

func rerankFixture(t *testing.T) *store.Store {
	t.Helper()
	ctx := context.Background()
	value, err := store.Open(ctx, filepath.Join(t.TempDir(), "search.sqlite3"), "/repo", "root")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { value.Close() })
	for index, text := range []string{
		"retry budget retry budget retry budget",
		"retry budget for uploads",
		"the budget is spent once per retry",
		"budget notes",
	} {
		path := fmt.Sprintf("src/file%d.go", index)
		file := discovery.File{
			Candidate: discovery.Candidate{Path: path, Size: int64(len(text)), MTimeNS: 1},
			Kind:      "go",
		}
		if err := value.ReplaceFile(ctx, "run", file, text, "fingerprint", []contracts.SearchRecord{{
			Path: path, StartLine: 1, EndLine: 1, Kind: "text-chunk", Text: text, Source: "core",
		}}); err != nil {
			t.Fatal(err)
		}
	}
	return value
}

func resultPaths(result Result) []string {
	paths := make([]string, 0, len(result.Records)+len(result.MorePaths))
	for _, record := range result.Records {
		paths = append(paths, record.Path)
	}
	return append(paths, result.MorePaths...)
}

func TestRerankFusesRerankerRankWithFusedRank(t *testing.T) {
	value := rerankFixture(t)
	ctx := context.Background()
	base, err := Run(ctx, value, "retry budget", Options{Mode: "lexical", Limit: 2})
	if err != nil {
		t.Fatal(err)
	}
	original := resultPaths(base)
	if len(original) != 4 || base.Rerank != nil {
		t.Fatalf("base=%v rerank=%#v", original, base.Rerank)
	}
	last := original[len(original)-1]
	reranker := &fakeReranker{score: func(input rerankcontract.Input) float64 {
		if strings.HasPrefix(input.Text, last+"\n") {
			return 1
		}
		return 0
	}}
	result, err := Run(ctx, value, "retry budget", Options{
		Mode: "lexical", Limit: 2,
		Rerank: &rerankcontract.Capability{Provider: reranker, Depth: 4},
	})
	if err != nil {
		t.Fatal(err)
	}
	if reranker.calls != 1 || reranker.query != "retry budget" || len(reranker.inputs) != 4 {
		t.Fatalf("calls=%d query=%q inputs=%d", reranker.calls, reranker.query, len(reranker.inputs))
	}
	// Reranker ranks: last=1, then original order. Fused rank 1 plus reranker
	// rank 2 still leads; the reranker's favourite rises from fourth to second.
	got := resultPaths(result)
	want := []string{original[0], last, original[1], original[2]}
	if fmt.Sprint(got) != fmt.Sprint(want) || len(result.Records) != 2 {
		t.Fatalf("reranked=%v want %v", got, want)
	}
	if result.Rerank == nil || result.Rerank.State != "applied" || result.Rerank.Candidates != 4 {
		t.Fatalf("status=%#v", result.Rerank)
	}
	for index, input := range reranker.inputs {
		if input.ID != fmt.Sprint(index) || !strings.HasPrefix(input.Text, original[index]+"\n") {
			t.Fatalf("input %d=%#v", index, input)
		}
	}
}

func TestRerankFailureKeepsFusedOrder(t *testing.T) {
	value := rerankFixture(t)
	ctx := context.Background()
	base, err := Run(ctx, value, "retry budget", Options{Mode: "lexical", Limit: 2})
	if err != nil {
		t.Fatal(err)
	}
	for _, reranker := range []*fakeReranker{
		{err: errors.New("worker crashed")},
		{score: func(rerankcontract.Input) float64 { return 0 }},
	} {
		capability := &rerankcontract.Capability{Provider: reranker}
		if reranker.err == nil {
			capability.Provider = duplicateScores{reranker}
		}
		result, err := Run(ctx, value, "retry budget", Options{Mode: "lexical", Limit: 2, Rerank: capability})
		if err != nil {
			t.Fatal(err)
		}
		if fmt.Sprint(resultPaths(result)) != fmt.Sprint(resultPaths(base)) {
			t.Fatalf("failed rerank changed order: %v", resultPaths(result))
		}
		if result.Rerank == nil || result.Rerank.State != "failed" || result.Rerank.Error == "" {
			t.Fatalf("status=%#v", result.Rerank)
		}
	}
}

type duplicateScores struct{ *fakeReranker }

func (d duplicateScores) Rerank(
	ctx context.Context,
	query string,
	inputs []rerankcontract.Input,
) ([]rerankcontract.Score, error) {
	scores, err := d.fakeReranker.Rerank(ctx, query, inputs)
	if len(scores) > 1 {
		scores[1].ID = scores[0].ID
	}
	return scores, err
}

func TestRerankSkipsStructuredQueriesAndValidatesCapability(t *testing.T) {
	value := rerankFixture(t)
	ctx := context.Background()
	reranker := &fakeReranker{score: func(rerankcontract.Input) float64 { return 0 }}
	capability := &rerankcontract.Capability{Provider: reranker}
	for _, options := range []Options{
		{Mode: "lexical", MatchKind: MatchFixed, Rerank: capability},
		{Mode: "lexical", Rerank: capability},
	} {
		query := "retry budget"
		if options.MatchKind == "" {
			query = "retryBudget"
		}
		result, err := Run(ctx, value, query, options)
		if err != nil {
			t.Fatal(err)
		}
		if result.Rerank != nil {
			t.Fatalf("%q reranked: %#v", query, result.Rerank)
		}
	}
	if reranker.calls != 0 {
		t.Fatalf("calls=%d", reranker.calls)
	}
	for _, invalid := range []*rerankcontract.Capability{
		{}, {Provider: reranker, Depth: -1}, {Provider: reranker, MaxInputChars: -1},
	} {
		if _, err := Run(ctx, value, "retry budget", Options{Rerank: invalid}); err == nil {
			t.Fatalf("accepted %#v", invalid)
		}
	}
}

func TestTruncateRunesKeepsWholeRunes(t *testing.T) {
	if got := truncateRunes("åäö-tail", 3); got != "åäö" {
		t.Fatalf("got %q", got)
	}
	if got := truncateRunes("short", 10); got != "short" {
		t.Fatalf("got %q", got)
	}
}

func TestRerankKeepsSupplementaryPaths(t *testing.T) {
	result := Result{
		Query: "q",
		ranked: []RankedRecord{
			{Path: "a.go", evidence: "a"}, {Path: "b.go", evidence: "b"}, {Path: "c.go", evidence: "c"},
		},
	}
	splitRanked(&result, 1)
	result.MorePaths = append(result.MorePaths, "semantic-only.go")
	reranker := &fakeReranker{score: func(input rerankcontract.Input) float64 {
		if strings.HasPrefix(input.Text, "c.go\n") {
			return 1
		}
		return 0
	}}
	rerankResult(context.Background(), &result, 1, rerankcontract.Capability{Provider: reranker})
	if got := fmt.Sprint(resultPaths(result)); got != "[a.go c.go b.go semantic-only.go]" {
		t.Fatalf("paths=%s", got)
	}
}

func TestRerankSkipsQueriesOverTheInputLimit(t *testing.T) {
	value := rerankFixture(t)
	reranker := &fakeReranker{score: func(rerankcontract.Input) float64 { return 0 }}
	capability := &rerankcontract.Capability{Provider: reranker, MaxInputChars: 40}
	query := "retry budget " + strings.Repeat("context ", 50)
	result, err := Run(context.Background(), value, query, Options{Mode: "lexical", Limit: 2, Rerank: capability})
	if err != nil {
		t.Fatal(err)
	}
	if reranker.calls != 0 || result.Rerank == nil || result.Rerank.State != "skipped" {
		t.Fatalf("calls=%d status=%#v", reranker.calls, result.Rerank)
	}
}
