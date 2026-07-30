package cli

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/coadan/yggdrasil/internal/project"
	"github.com/coadan/yggdrasil/internal/store"
)

func TestIndexSearchAndStatus(t *testing.T) {
	isolateCLIUserConfig(t)
	root := t.TempDir()
	t.Setenv("YGG_STORAGE_ROOT", t.TempDir())
	if err := os.WriteFile(filepath.Join(root, "router.go"), []byte("package router\nfunc RegisterRequest() {}\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	var stdout, stderr bytes.Buffer
	if code := Main(context.Background(), []string{"index", "--root", root}, &stdout, &stderr); code != 0 {
		t.Fatalf("index code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
	paths, err := project.Resolve(root)
	if err != nil {
		t.Fatal(err)
	}
	latestBefore := latestRunID(t, paths)
	stdout.Reset()
	stderr.Reset()
	if code := Main(context.Background(), []string{"index", "--root", root}, &stdout, &stderr); code != 0 {
		t.Fatalf("current index code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
	var indexResponse struct {
		Data struct {
			RunID    string `json:"runId"`
			UpToDate bool   `json:"upToDate"`
		} `json:"data"`
	}
	if err := json.Unmarshal(stdout.Bytes(), &indexResponse); err != nil {
		t.Fatal(err)
	}
	if !indexResponse.Data.UpToDate || indexResponse.Data.RunID != "" {
		t.Fatalf("unexpected current index response: %s", stdout.String())
	}
	if latestAfter := latestRunID(t, paths); latestAfter != latestBefore {
		t.Fatalf("current index created run %q after %q", latestAfter, latestBefore)
	}
	stdout.Reset()
	stderr.Reset()
	if code := Main(context.Background(), []string{"search", "--root", root, "--mode", "lexical", "RegisterRequest"}, &stdout, &stderr); code != 0 {
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

func TestSearchInitializesAnUnindexedRepository(t *testing.T) {
	isolateCLIUserConfig(t)
	root := t.TempDir()
	t.Setenv("YGG_STORAGE_ROOT", t.TempDir())
	if err := os.WriteFile(
		filepath.Join(root, "owner.txt"), []byte("firstsearchmarker\n"), 0o644,
	); err != nil {
		t.Fatal(err)
	}
	var stdout, stderr bytes.Buffer
	code := Main(
		context.Background(),
		[]string{"search", "--root", root, "--mode", "lexical", "firstsearchmarker"},
		&stdout,
		&stderr,
	)
	if code != 0 {
		t.Fatalf("code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
	assertSearchPaths(t, stdout.Bytes(), []string{"owner.txt"})
	paths, err := project.Resolve(root)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(paths.Database); err != nil {
		t.Fatalf("search did not initialize the index: %v", err)
	}
}

func TestAutoSearchCompletesConfiguredEmbeddingsButLexicalDoesNot(t *testing.T) {
	isolateCLIUserConfig(t)
	requests := 0
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		requests++
		var body struct {
			Input []string `json:"input"`
		}
		if err := json.NewDecoder(request.Body).Decode(&body); err != nil {
			t.Error(err)
			return
		}
		data := make([]map[string]any, len(body.Input))
		for index := range body.Input {
			data[index] = map[string]any{"index": index, "embedding": []float32{1, 0}}
		}
		_ = json.NewEncoder(writer).Encode(map[string]any{"data": data})
	}))
	defer server.Close()

	root := t.TempDir()
	t.Setenv("YGG_STORAGE_ROOT", t.TempDir())
	if err := os.WriteFile(
		filepath.Join(root, "owner.txt"), []byte("embeddingreadinessmarker\n"), 0o644,
	); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(filepath.Join(root, ".ygg"), 0o755); err != nil {
		t.Fatal(err)
	}
	rawConfig, err := json.Marshal(map[string]any{
		"schema": "ygg.config/v1",
		"embedding": map[string]any{
			"kind": "openai-compatible", "endpoint": server.URL, "model": "test",
			"dimensions": 2, "timeoutMs": 1_000, "batchSize": 8,
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, ".ygg", "config.json"), rawConfig, 0o644); err != nil {
		t.Fatal(err)
	}

	var stdout, stderr bytes.Buffer
	if code := Main(context.Background(), []string{
		"index", "--root", root, "--no-embed",
	}, &stdout, &stderr); code != 0 {
		t.Fatalf("index code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
	stdout.Reset()
	stderr.Reset()
	if code := Main(context.Background(), []string{
		"search", "--root", root, "--mode", "lexical", "embeddingreadinessmarker",
	}, &stdout, &stderr); code != 0 {
		t.Fatalf("lexical code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
	if requests != 0 {
		t.Fatalf("lexical search called embedding provider %d times", requests)
	}

	stdout.Reset()
	stderr.Reset()
	if code := Main(context.Background(), []string{
		"search", "--root", root, "embedding readiness",
	}, &stdout, &stderr); code != 0 {
		t.Fatalf("auto code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
	var response struct {
		Data struct {
			ActiveMode     string `json:"activeMode"`
			FallbackReason string `json:"fallbackReason"`
		} `json:"data"`
	}
	if err := json.Unmarshal(stdout.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	if response.Data.ActiveMode != "hybrid" || response.Data.FallbackReason != "" || requests < 2 {
		t.Fatalf("requests=%d response=%s", requests, stdout.String())
	}
}

func TestSearchAcceptsFlagsAfterQueryAndLegacyJSONAssertion(t *testing.T) {
	isolateCLIUserConfig(t)
	root := t.TempDir()
	t.Setenv("YGG_STORAGE_ROOT", t.TempDir())
	if err := os.WriteFile(
		filepath.Join(root, "owner.txt"), []byte("trailingflagmarker\n"), 0o644,
	); err != nil {
		t.Fatal(err)
	}
	var stdout, stderr bytes.Buffer
	if code := Main(context.Background(), []string{
		"index", "--root", root, "--no-embed",
	}, &stdout, &stderr); code != 0 {
		t.Fatalf("index code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
	stdout.Reset()
	stderr.Reset()
	if code := Main(context.Background(), []string{
		"search", "trailingflagmarker", "--root", root,
		"--mode", "lexical", "--limit", "1", "--json",
	}, &stdout, &stderr); code != 0 {
		t.Fatalf("search code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
	var response struct {
		OK   bool `json:"ok"`
		Data struct {
			RequestedMode string `json:"requestedMode"`
			Records       []struct {
				Path string `json:"path"`
			} `json:"records"`
		} `json:"data"`
	}
	if err := json.Unmarshal(stdout.Bytes(), &response); err != nil {
		t.Fatalf("search did not write default JSON: %v: %s", err, stdout.String())
	}
	if !response.OK || response.Data.RequestedMode != "lexical" ||
		len(response.Data.Records) != 1 || response.Data.Records[0].Path != "owner.txt" {
		t.Fatalf("unexpected search response: %s", stdout.String())
	}
}

func TestSearchRepositorySubdirectoryReusesRootIndex(t *testing.T) {
	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("git is not installed")
	}
	isolateCLIUserConfig(t)
	root := t.TempDir()
	nested := filepath.Join(root, "tests", "browser")
	if err := os.MkdirAll(nested, 0o755); err != nil {
		t.Fatal(err)
	}
	t.Setenv("YGG_STORAGE_ROOT", t.TempDir())
	cliRunGit(t, root, "init", "-q")
	ownerPath := filepath.Join(nested, "owner.spec.ts")
	if err := os.WriteFile(
		ownerPath, []byte("subdirectoryrootmarker\n"), 0o644,
	); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(
		filepath.Join(root, "outside.spec.ts"), []byte("subdirectoryrootmarker\n"), 0o644,
	); err != nil {
		t.Fatal(err)
	}
	var stdout, stderr bytes.Buffer
	if code := Main(context.Background(), []string{
		"index", "--root", root, "--no-embed",
	}, &stdout, &stderr); code != 0 {
		t.Fatalf("index code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
	rootPaths, err := project.Resolve(root)
	if err != nil {
		t.Fatal(err)
	}
	nestedPaths, err := project.Resolve(nested)
	if err != nil {
		t.Fatal(err)
	}
	if nestedPaths.Database != rootPaths.Database {
		t.Fatalf("nested database=%q root database=%q", nestedPaths.Database, rootPaths.Database)
	}

	stdout.Reset()
	stderr.Reset()
	if code := Main(context.Background(), []string{
		"search", "subdirectoryrootmarker", "--root", nested, "--mode", "lexical",
	}, &stdout, &stderr); code != 0 {
		t.Fatalf("search code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
	var response struct {
		Data struct {
			Records []struct {
				Path string `json:"path"`
			} `json:"records"`
		} `json:"data"`
	}
	if err := json.Unmarshal(stdout.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	if len(response.Data.Records) != 1 ||
		response.Data.Records[0].Path != "tests/browser/owner.spec.ts" {
		t.Fatalf("response=%s", stdout.String())
	}

	stdout.Reset()
	stderr.Reset()
	if code := Main(context.Background(), []string{
		"search", "subdirectoryrootmarker", "--root", ownerPath, "--mode", "lexical",
	}, &stdout, &stderr); code != 0 {
		t.Fatalf("file search code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
	assertSearchPaths(t, stdout.Bytes(), []string{"tests/browser/owner.spec.ts"})
}

func TestSearchRefreshesModifiedAndDeletedFiles(t *testing.T) {
	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("git is not installed")
	}
	isolateCLIUserConfig(t)
	root := t.TempDir()
	t.Setenv("YGG_STORAGE_ROOT", t.TempDir())
	cliRunGit(t, root, "init", "-q")
	owner := filepath.Join(root, "owner.txt")
	if err := os.WriteFile(owner, []byte("retiredsearchmarker\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	cliRunGit(t, root, "add", "owner.txt")
	cliRunGit(t, root, "config", "user.name", "Ygg Test")
	cliRunGit(t, root, "config", "user.email", "ygg@example.test")
	cliRunGit(t, root, "commit", "-qm", "fixture")
	var stdout, stderr bytes.Buffer
	if code := Main(context.Background(), []string{
		"index", "--root", root, "--no-embed",
	}, &stdout, &stderr); code != 0 {
		t.Fatalf("index code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
	if err := os.WriteFile(owner, []byte("currentsearchmarker\n"), 0o644); err != nil {
		t.Fatal(err)
	}

	stdout.Reset()
	stderr.Reset()
	if code := Main(context.Background(), []string{
		"search", "--root", root, "--mode", "lexical", "currentsearchmarker",
	}, &stdout, &stderr); code != 0 {
		t.Fatalf("modified search code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
	assertSearchPaths(t, stdout.Bytes(), []string{"owner.txt"})

	paths, err := project.Resolve(root)
	if err != nil {
		t.Fatal(err)
	}
	latestBefore := latestRunID(t, paths)
	stdout.Reset()
	stderr.Reset()
	if code := Main(context.Background(), []string{
		"search", "--root", root, "--mode", "lexical", "currentsearchmarker",
	}, &stdout, &stderr); code != 0 {
		t.Fatalf("unchanged search code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
	if latestAfter := latestRunID(t, paths); latestAfter != latestBefore {
		t.Fatalf("unchanged search created index run %q after %q", latestAfter, latestBefore)
	}

	if err := os.WriteFile(owner, []byte("parallelsearchmarker\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	start := make(chan struct{})
	results := make(chan struct {
		code   int
		stdout []byte
		stderr string
	}, 2)
	for range 2 {
		go func() {
			var concurrentOut, concurrentErr bytes.Buffer
			<-start
			code := Main(context.Background(), []string{
				"search", "--root", root, "--mode", "lexical", "parallelsearchmarker",
			}, &concurrentOut, &concurrentErr)
			results <- struct {
				code   int
				stdout []byte
				stderr string
			}{code: code, stdout: concurrentOut.Bytes(), stderr: concurrentErr.String()}
		}()
	}
	close(start)
	for range 2 {
		result := <-results
		if result.code != 0 {
			t.Fatalf(
				"concurrent search code=%d stdout=%s stderr=%s",
				result.code,
				result.stdout,
				result.stderr,
			)
		}
		assertSearchPaths(t, result.stdout, []string{"owner.txt"})
	}

	untracked := filepath.Join(root, "new-owner.txt")
	if err := os.WriteFile(untracked, []byte("untrackedsearchmarker\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	stdout.Reset()
	stderr.Reset()
	if code := Main(context.Background(), []string{
		"search", "--root", root, "--mode", "lexical", "untrackedsearchmarker",
	}, &stdout, &stderr); code != 0 {
		t.Fatalf("untracked search code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
	assertSearchPaths(t, stdout.Bytes(), []string{"new-owner.txt"})

	if err := os.Remove(owner); err != nil {
		t.Fatal(err)
	}
	stdout.Reset()
	stderr.Reset()
	if code := Main(context.Background(), []string{
		"search", "--root", root, "--mode", "lexical", "currentsearchmarker",
	}, &stdout, &stderr); code != 0 {
		t.Fatalf("deleted search code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
	assertSearchPaths(t, stdout.Bytes(), nil)
}

func latestRunID(t *testing.T, paths project.Paths) string {
	t.Helper()
	value, err := store.Open(context.Background(), paths.Database, paths.Root, paths.ID)
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	run, err := value.LatestRun(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if run == nil {
		t.Fatal("missing latest index run")
	}
	return run.ID
}

func assertSearchPaths(t *testing.T, output []byte, want []string) {
	t.Helper()
	var response struct {
		Data struct {
			Records []struct {
				Path string `json:"path"`
			} `json:"records"`
		} `json:"data"`
	}
	if err := json.Unmarshal(output, &response); err != nil {
		t.Fatal(err)
	}
	got := make([]string, 0, len(response.Data.Records))
	for _, record := range response.Data.Records {
		got = append(got, record.Path)
	}
	if strings.Join(got, ",") != strings.Join(want, ",") {
		t.Fatalf("paths=%q want=%q response=%s", got, want, output)
	}
}

func TestSearchLazilySeedsLinkedWorktreeIndex(t *testing.T) {
	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("git is not installed")
	}
	isolateCLIUserConfig(t)
	base := t.TempDir()
	t.Setenv("YGG_STORAGE_ROOT", filepath.Join(base, "state"))
	primary := filepath.Join(base, "primary")
	linked := filepath.Join(base, "linked")
	if err := os.Mkdir(primary, 0o755); err != nil {
		t.Fatal(err)
	}
	cliRunGit(t, primary, "init", "-q")
	cliRunGit(t, primary, "config", "user.name", "Ygg Test")
	cliRunGit(t, primary, "config", "user.email", "ygg@example.test")
	if err := os.WriteFile(
		filepath.Join(primary, "owner.txt"), []byte("lazyworktreeseedmarker\n"), 0o644,
	); err != nil {
		t.Fatal(err)
	}
	cliRunGit(t, primary, "add", "owner.txt")
	cliRunGit(t, primary, "commit", "-qm", "fixture")
	cliRunGit(t, primary, "worktree", "add", "-qb", "linked", linked)

	var stdout, stderr bytes.Buffer
	if code := Main(context.Background(), []string{
		"index", "--root", primary, "--no-embed",
	}, &stdout, &stderr); code != 0 {
		t.Fatalf("index code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
	linkedPaths, err := project.Resolve(linked)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(linkedPaths.Database); !os.IsNotExist(err) {
		t.Fatalf("linked index exists before search: %v", err)
	}
	stdout.Reset()
	stderr.Reset()
	if code := Main(context.Background(), []string{
		"search", "--root", linked, "--mode", "lexical", "lazyworktreeseedmarker",
	}, &stdout, &stderr); code != 0 {
		t.Fatalf("search code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
	if _, err := os.Stat(linkedPaths.Database); err != nil {
		t.Fatalf("linked index was not created: %v", err)
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
	if !response.OK || len(response.Data.Records) != 1 ||
		response.Data.Records[0].Path != "owner.txt" {
		t.Fatalf("response=%s", stdout.String())
	}
}

func TestSearchWaitsForConcurrentIndexCommit(t *testing.T) {
	isolateCLIUserConfig(t)
	root := t.TempDir()
	t.Setenv("YGG_STORAGE_ROOT", t.TempDir())
	if err := os.WriteFile(filepath.Join(root, "retired-owner.txt"), []byte("retired-owner\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	var stdout, stderr bytes.Buffer
	if code := Main(context.Background(), []string{
		"index", "--root", root, "--no-embed",
	}, &stdout, &stderr); code != 0 {
		t.Fatalf("index code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
	paths, err := project.Resolve(root)
	if err != nil {
		t.Fatal(err)
	}
	lock, err := os.OpenFile(paths.IndexLock, os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		t.Fatal(err)
	}
	defer lock.Close()
	if err := syscall.Flock(int(lock.Fd()), syscall.LOCK_EX); err != nil {
		t.Fatal(err)
	}
	locked := true
	defer func() {
		if locked {
			_ = syscall.Flock(int(lock.Fd()), syscall.LOCK_UN)
		}
	}()

	stdout.Reset()
	stderr.Reset()
	done := make(chan int, 1)
	go func() {
		done <- Main(context.Background(), []string{
			"search", "--root", root, "--mode", "lexical", "retired-owner",
		}, &stdout, &stderr)
	}()
	select {
	case code := <-done:
		t.Fatalf("search returned before index commit: code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	case <-time.After(50 * time.Millisecond):
	}

	value, err := store.Open(context.Background(), paths.Database, paths.Root, paths.ID)
	if err != nil {
		t.Fatal(err)
	}
	if err := value.DeleteFile(context.Background(), "retired-owner.txt"); err != nil {
		value.Close()
		t.Fatal(err)
	}
	if err := value.Close(); err != nil {
		t.Fatal(err)
	}
	if err := syscall.Flock(int(lock.Fd()), syscall.LOCK_UN); err != nil {
		t.Fatal(err)
	}
	locked = false

	select {
	case code := <-done:
		if code != 0 {
			t.Fatalf("search code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
		}
	case <-time.After(2 * time.Second):
		t.Fatal("search did not resume after index commit")
	}
	var response struct {
		OK   bool `json:"ok"`
		Data struct {
			Records []json.RawMessage `json:"records"`
		} `json:"data"`
	}
	if err := json.Unmarshal(stdout.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	if !response.OK || len(response.Data.Records) != 0 {
		t.Fatalf("search observed stale records: %s", stdout.String())
	}
}

func TestVersionProbes(t *testing.T) {
	for _, args := range [][]string{{"--version"}, {"-version"}, {"version"}} {
		var stdout, stderr bytes.Buffer
		if code := Main(context.Background(), args, &stdout, &stderr); code != 0 {
			t.Fatalf("args=%v code=%d stderr=%s", args, code, stderr.String())
		}
		if got, want := stdout.String(), "ygg "+Version+"\n"; got != want {
			t.Fatalf("args=%v output=%q want=%q", args, got, want)
		}
	}
}

func TestSearchFindsLiteralJSONFlagAfterTerminator(t *testing.T) {
	isolateCLIUserConfig(t)
	root := t.TempDir()
	t.Setenv("YGG_STORAGE_ROOT", t.TempDir())
	if err := os.WriteFile(
		filepath.Join(root, "flags.md"), []byte("Output is selected with --json.\n"), 0o644,
	); err != nil {
		t.Fatal(err)
	}
	var stdout, stderr bytes.Buffer
	if code := Main(context.Background(), []string{
		"index", "--root", root, "--no-embed",
	}, &stdout, &stderr); code != 0 {
		t.Fatalf("index code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
	stdout.Reset()
	stderr.Reset()
	if code := Main(context.Background(), []string{
		"search", "--root", root, "--mode", "lexical", "--", "--json",
	}, &stdout, &stderr); code != 0 {
		t.Fatalf("search code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
	var response struct {
		Data struct {
			Query   string `json:"query"`
			Records []struct {
				Path string `json:"path"`
			} `json:"records"`
		} `json:"data"`
	}
	if err := json.Unmarshal(stdout.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	if response.Data.Query != "--json" || len(response.Data.Records) != 1 ||
		response.Data.Records[0].Path != "flags.md" {
		t.Fatalf("response=%s", stdout.String())
	}
}

func TestSearchExplainsLeadingDashQuerySeparator(t *testing.T) {
	var stdout, stderr bytes.Buffer
	code := Main(
		context.Background(),
		[]string{"search", "--literal-looking-query"},
		&stdout,
		&stderr,
	)
	if code != 2 || stderr.Len() != 0 ||
		!strings.Contains(stdout.String(), "place -- before a query that begins with '-'") {
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
	isolateCLIUserConfig(t)
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

func TestOperationalHelpOmitsRedundantJSONFlagAndSucceeds(t *testing.T) {
	for _, args := range [][]string{
		{"index", "--help"},
		{"search", "--help"},
		{"status", "--help"},
		{"plugin", "check", "example", "--help"},
	} {
		var stdout, stderr bytes.Buffer
		code := Main(context.Background(), args, &stdout, &stderr)
		if code != 0 || stdout.Len() != 0 || strings.Contains(stderr.String(), "json") {
			t.Fatalf("args=%v code=%d stdout=%s stderr=%s", args, code, stdout.String(), stderr.String())
		}
	}
	var stdout, stderr bytes.Buffer
	if code := Main(context.Background(), []string{"help"}, &stdout, &stderr); code != 0 ||
		strings.Contains(stdout.String(), "--json") || stderr.Len() != 0 {
		t.Fatalf("top-level help code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
}

func TestSearchHelpNamesCanonicalJSONPaths(t *testing.T) {
	var stdout, stderr bytes.Buffer
	code := Main(
		context.Background(),
		[]string{"search", "--help"},
		&stdout,
		&stderr,
	)
	if code != 0 || stdout.Len() != 0 ||
		!strings.Contains(stderr.String(), "result records: data.records") ||
		!strings.Contains(stderr.String(), "additional candidate paths, when present: data.morePaths") {
		t.Fatalf("code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
}

func TestOperationalCommandsRejectNonJSONOutputSelector(t *testing.T) {
	for _, args := range [][]string{
		{"index", "--json=false"},
		{"search", "query", "--json=false"},
		{"status", "--json=false"},
		{"plugin", "check", "example", "--json=false"},
	} {
		var stdout, stderr bytes.Buffer
		code := Main(context.Background(), args, &stdout, &stderr)
		if code != 2 || stderr.Len() != 0 ||
			!strings.Contains(stdout.String(), `"ok":false`) ||
			!strings.Contains(stdout.String(), "flag provided but not defined: -json") {
			t.Fatalf("args=%v code=%d stdout=%s stderr=%s", args, code, stdout.String(), stderr.String())
		}
	}
}

func TestSearchRejectsPathAliasWithCanonicalScopeGuidance(t *testing.T) {
	var stdout, stderr bytes.Buffer
	code := Main(
		context.Background(),
		[]string{"search", "query", "--path", "src"},
		&stdout,
		&stderr,
	)
	if code != 2 || stderr.Len() != 0 ||
		!strings.Contains(stdout.String(), `"ok":false`) ||
		!strings.Contains(
			stdout.String(),
			"--path is not supported; use --root PATH for repository, directory, or file scope",
		) ||
		strings.Contains(stdout.String(), "Usage of search") {
		t.Fatalf("code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
}

func isolateCLIUserConfig(t *testing.T) {
	t.Helper()
	t.Setenv("YGG_CONFIG", "")
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
}

func cliRunGit(t *testing.T, root string, args ...string) {
	t.Helper()
	command := exec.Command("git", append([]string{"-C", root}, args...)...)
	if output, err := command.CombinedOutput(); err != nil {
		t.Fatalf("git %v: %v: %s", args, err, output)
	}
}
