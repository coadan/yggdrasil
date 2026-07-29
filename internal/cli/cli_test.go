package cli

import (
	"bytes"
	"context"
	"encoding/json"
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

func TestSearchRequiresAnIndex(t *testing.T) {
	isolateCLIUserConfig(t)
	root := t.TempDir()
	t.Setenv("YGG_STORAGE_ROOT", t.TempDir())
	var stdout, stderr bytes.Buffer
	code := Main(context.Background(), []string{"search", "--root", root, "query"}, &stdout, &stderr)
	if code != 3 {
		t.Fatalf("code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
}

func TestSearchAcceptsFlagsAfterQuery(t *testing.T) {
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
		"--mode", "lexical", "--limit", "1",
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

func TestOperationalCommandsRejectRemovedJSONFlag(t *testing.T) {
	for _, args := range [][]string{
		{"index", "--json"},
		{"search", "query", "--json"},
		{"status", "--json"},
		{"plugin", "check", "example", "--json"},
	} {
		var stdout, stderr bytes.Buffer
		code := Main(context.Background(), args, &stdout, &stderr)
		if code != 2 || !strings.Contains(stderr.String(), "flag provided but not defined: -json") {
			t.Fatalf("args=%v code=%d stdout=%s stderr=%s", args, code, stdout.String(), stderr.String())
		}
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
