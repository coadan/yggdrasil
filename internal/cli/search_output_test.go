package cli

import (
	"bytes"
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/coadan/yggdrasil/query"
)

func TestSearchCompactOutputWithCitationsAndOverflow(t *testing.T) {
	isolateCLIUserConfig(t)
	root := t.TempDir()
	t.Setenv("YGG_STORAGE_ROOT", t.TempDir())
	for _, name := range []string{"a.txt", "b.txt"} {
		if err := os.WriteFile(filepath.Join(root, name), []byte("needle\nsecond line\n"), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	args := []string{"search", "--root", root, "--mode", "lexical", "--limit", "1", "-F", "needle"}
	var stdout, stderr bytes.Buffer
	if code := Main(context.Background(), append(args, "--format", "compact"), &stdout, &stderr); code != 0 {
		t.Fatalf("compact code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
	const want = "1 results (lexical)\na.txt:1-2\n    needle\n    second line\nmore: b.txt\n"
	if stdout.String() != want {
		t.Fatalf("compact stdout=%q, want %q", stdout.String(), want)
	}
	if stderr.Len() != 0 {
		// This first search initializes the index; compact keeps that quiet.
		t.Fatalf("compact search wrote progress: %s", stderr.String())
	}
	for _, invocation := range [][]string{args, append(args, "--format=json")} {
		stdout.Reset()
		stderr.Reset()
		if code := Main(context.Background(), invocation, &stdout, &stderr); code != 0 {
			t.Fatalf("json code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
		}
		var response struct {
			Schema string       `json:"schema"`
			OK     bool         `json:"ok"`
			Data   query.Result `json:"data"`
		}
		if err := json.Unmarshal(stdout.Bytes(), &response); err != nil || !response.OK || response.Schema != "ygg.cli/v1" {
			t.Fatalf("json response=%s err=%v", stdout.String(), err)
		}
		if len(response.Data.Records) != 1 || response.Data.Records[0].Path != "a.txt" ||
			len(response.Data.MorePaths) != 1 || response.Data.MorePaths[0] != "b.txt" {
			t.Fatalf("json changed paths or order: %s", stdout.String())
		}
	}
}

func TestCompactSearchEscapesControlsAndAmbiguousPaths(t *testing.T) {
	var stdout, stderr bytes.Buffer
	r := runner{stdout: &stdout, stderr: &stderr}
	result := query.Result{
		ActiveMode: "lexical",
		Records: []query.RankedRecord{{
			Path: "file\nname.txt", StartLine: 4, EndLine: 5,
			Excerpt: "normal\r\nline\x1b[2J\n",
		}},
		MorePaths: []string{"more: name.txt", "comma, name.txt"},
	}
	const want = "1 results (lexical)\n\"file\\nname.txt\":4-5\n    normal\n    line\\x1b[2J\n" +
		"more: \"more: name.txt\", \"comma, name.txt\"\n"
	if code := r.writeSearchResult(result, "compact"); code != 0 || stdout.String() != want || stderr.Len() != 0 {
		t.Fatalf("code=%d stdout=%q stderr=%q, want %q", code, stdout.String(), stderr.String(), want)
	}
}

func TestSearchFormatErrorsKeepJSONEnvelope(t *testing.T) {
	for _, tc := range []struct {
		args []string
		want string
	}{
		{[]string{"search", "--format", "files", "needle"}, `unsupported search format`},
		{[]string{"search", "--format", "compact"}, `search query is required`},
	} {
		var stdout, stderr bytes.Buffer
		if code := Main(context.Background(), tc.args, &stdout, &stderr); code != 2 ||
			!strings.Contains(stdout.String(), `"schema":"ygg.cli/v1","ok":false`) ||
			!strings.Contains(stdout.String(), tc.want) || stderr.Len() != 0 {
			t.Fatalf("args=%v code=%d stdout=%q stderr=%q", tc.args, code, stdout.String(), stderr.String())
		}
	}
}

func TestSearchCitesTheLinesItShowsAfterBlankLines(t *testing.T) {
	isolateCLIUserConfig(t)
	root := t.TempDir()
	t.Setenv("YGG_STORAGE_ROOT", t.TempDir())
	files := map[string]string{
		// Leading blank lines are trimmed from the chunk.
		"lead.txt": "\n\n\nfirst\nsecond\nleadmarker here\nthird\n",
		// The citation starts two lines before the match, on blank lines.
		"gap.txt": "a\nb\nc\n\n\ngap.marker here\ne\n",
	}
	for name, text := range files {
		if err := os.WriteFile(filepath.Join(root, name), []byte(text), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	for query, want := range map[string]string{
		"leadmarker": "lead.txt:4-7\n    first\n",
		"gap.marker": "gap.txt:6-7\n    gap.marker here\n",
	} {
		var stdout, stderr bytes.Buffer
		if code := Main(context.Background(), []string{
			"search", "--root", root, "--mode", "lexical", "--format", "compact", query,
		}, &stdout, &stderr); code != 0 {
			t.Fatalf("%s code=%d stdout=%s stderr=%s", query, code, stdout.String(), stderr.String())
		}
		if !strings.Contains(stdout.String(), want) {
			t.Fatalf("%s output=%q, want %q", query, stdout.String(), want)
		}
	}
}
