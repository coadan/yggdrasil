package query

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestRunFilesystemUsesCurrentFilesAndCitations(t *testing.T) {
	root := t.TempDir()
	if err := os.MkdirAll(filepath.Join(root, "src"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(
		filepath.Join(root, "src", "owner.go"),
		[]byte("package owner\n\nfunc pushErrorEnvelope() {}\n"),
		0o644,
	); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(
		filepath.Join(root, "retired.go"), []byte("retired marker\n"), 0o644,
	); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(filepath.Join(root, "retired.go")); err != nil {
		t.Fatal(err)
	}

	result, err := RunFilesystem(
		context.Background(), root, "push error envelope",
		FilesystemOptions{Limit: 10, RequestedMode: "auto"},
	)
	if err != nil {
		t.Fatal(err)
	}
	if result.ActiveMode != "lexical" || result.FallbackReason != "index-busy" ||
		result.RequestedMode != "auto" {
		t.Fatalf("result=%#v", result)
	}
	if len(result.Records) != 1 || result.Records[0].Path != "src/owner.go" ||
		result.Records[0].StartLine != 1 || result.Records[0].EndLine < 3 {
		t.Fatalf("records=%#v", result.Records)
	}
}

func TestRunFilesystemKeepsStructuredAbsenceExact(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(
		filepath.Join(root, "retired-class-name.go"),
		[]byte("package current\n"), 0o644,
	); err != nil {
		t.Fatal(err)
	}
	result, err := RunFilesystem(
		context.Background(), root, "retired-class-name",
		FilesystemOptions{Limit: 10, RequestedMode: "lexical"},
	)
	if err != nil {
		t.Fatal(err)
	}
	if len(result.Records) != 0 {
		t.Fatalf("records=%#v", result.Records)
	}
}

func TestRunFilesystemSupportsFixedAndRegexpPlans(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(
		filepath.Join(root, "owner.go"),
		[]byte("func pushCommandErrorEnvelope() {}\n"), 0o644,
	); err != nil {
		t.Fatal(err)
	}
	for _, test := range []struct {
		pattern string
		kind    string
	}{
		{"push", MatchFixed},
		{`push.*ErrorEnvelope`, MatchRegexp},
	} {
		result, err := RunFilesystem(
			context.Background(), root, test.pattern,
			FilesystemOptions{Limit: 10, RequestedMode: "auto", MatchKind: test.kind},
		)
		if err != nil {
			t.Fatal(err)
		}
		if len(result.Records) != 1 || result.Records[0].Path != "owner.go" ||
			result.QueryPlan.Lexical.Kind != test.kind {
			t.Fatalf("kind=%s result=%#v", test.kind, result)
		}
	}
}

func TestRunFilesystemKeepsBestLateMatchAndBoundedOverflow(t *testing.T) {
	root := t.TempDir()
	for index := range 80 {
		if err := os.WriteFile(filepath.Join(root, fmt.Sprintf("a-%03d.txt", index)), []byte("fallback\n"), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.WriteFile(filepath.Join(root, "z-owner.txt"), []byte(strings.Repeat("padding\n", 32)+"fallback marker\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	result, err := RunFilesystem(context.Background(), root, "fallback marker", FilesystemOptions{Limit: 5})
	if err != nil {
		t.Fatal(err)
	}
	if len(result.Records) != 5 || len(result.MorePaths) != 20 {
		t.Fatalf("result counts=%d,%d", len(result.Records), len(result.MorePaths))
	}
	owner := result.Records[0]
	if owner.Path != "z-owner.txt" || owner.StartLine < 1 || owner.StartLine > 33 || owner.EndLine != 33 || !strings.Contains(owner.Excerpt, "fallback marker") {
		t.Fatalf("late best citation=%#v", owner)
	}
	lines := strings.Split(strings.Repeat("padding\n", 32)+"fallback marker\n", "\n")
	if want := strings.Join(lines[owner.StartLine-1:owner.EndLine], "\n"); owner.Excerpt != want {
		t.Fatalf("citation excerpt=%q source=%q", owner.Excerpt, want)
	}
	for index, record := range result.Records[1:] {
		if want := fmt.Sprintf("a-%03d.txt", index); record.Path != want {
			t.Fatalf("record=%s want %s", record.Path, want)
		}
	}
	for index, path := range result.MorePaths {
		if want := fmt.Sprintf("a-%03d.txt", index+4); path != want {
			t.Fatalf("overflow=%s want %s", path, want)
		}
	}
}

func TestRunFilesystemRejectsOutOfRangeLimits(t *testing.T) {
	for _, limit := range []int{-1, MaxResults + 1, int(^uint(0) >> 1)} {
		_, err := RunFilesystem(context.Background(), t.TempDir(), "marker", FilesystemOptions{Limit: limit})
		if err == nil || !strings.Contains(err.Error(), "limit") {
			t.Fatalf("limit=%d err=%v", limit, err)
		}
	}
}

func TestRunFilesystemHonorsCanceledContextOnEmptyRepository(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err := RunFilesystem(ctx, t.TempDir(), "marker", FilesystemOptions{})
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("err=%v", err)
	}
}
