package cli

import (
	"bytes"
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestIndexSearchAndStatus(t *testing.T) {
	root := t.TempDir()
	t.Setenv("YGG_STORAGE_ROOT", t.TempDir())
	if err := os.WriteFile(filepath.Join(root, "router.go"), []byte("package router\nfunc RegisterRequest() {}\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	var stdout, stderr bytes.Buffer
	if code := Main(context.Background(), []string{"index", "--root", root, "--json"}, &stdout, &stderr); code != 0 {
		t.Fatalf("index code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
	stdout.Reset()
	stderr.Reset()
	if code := Main(context.Background(), []string{"search", "--root", root, "--mode", "lexical", "--json", "RegisterRequest"}, &stdout, &stderr); code != 0 {
		t.Fatalf("search code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
	var response struct {
		OK   bool `json:"ok"`
		Data struct {
			Records []struct {
				Path string `json:"path"`
			} `json:"records"`
		} `json:"data"`
	}
	if err := json.Unmarshal(stdout.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	if !response.OK || len(response.Data.Records) == 0 || response.Data.Records[0].Path != "router.go" {
		t.Fatalf("unexpected search response: %s", stdout.String())
	}
	stdout.Reset()
	stderr.Reset()
	if code := Main(context.Background(), []string{"status", "--root", root}, &stdout, &stderr); code != 0 {
		t.Fatalf("status code=%d stderr=%s", code, stderr.String())
	}
	if stdout.Len() == 0 {
		t.Fatal("empty status output")
	}
}

func TestSearchRequiresAnIndex(t *testing.T) {
	root := t.TempDir()
	t.Setenv("YGG_STORAGE_ROOT", t.TempDir())
	var stdout, stderr bytes.Buffer
	code := Main(context.Background(), []string{"search", "--root", root, "query"}, &stdout, &stderr)
	if code != 3 {
		t.Fatalf("code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
}

func TestPublicSurfaceRejectsLegacyCommands(t *testing.T) {
	for _, command := range []string{
		"start", "init", "sync", "query", "view", "report", "packages",
		"maintenance", "agent", "bench", "embed", "watch",
	} {
		t.Run(command, func(t *testing.T) {
			var stdout, stderr bytes.Buffer
			if code := Main(context.Background(), []string{command}, &stdout, &stderr); code != 2 {
				t.Fatalf("code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
			}
		})
	}
	var stdout, stderr bytes.Buffer
	if code := Main(context.Background(), []string{"plugin", "install"}, &stdout, &stderr); code != 2 {
		t.Fatalf("plugin install code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
}

func TestSearchRejectsOutOfBoundsLimitBeforeOpeningIndex(t *testing.T) {
	root := t.TempDir()
	t.Setenv("YGG_STORAGE_ROOT", t.TempDir())
	var stdout, stderr bytes.Buffer
	code := Main(context.Background(), []string{
		"search", "--root", root, "--limit", "101", "query",
	}, &stdout, &stderr)
	if code != 2 {
		t.Fatalf("code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
}
