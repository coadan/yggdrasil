package query

import (
	"context"
	"os"
	"path/filepath"
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
