package cli

import (
	"bytes"
	"context"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"github.com/coadan/yggdrasil/internal/config"
	"github.com/coadan/yggdrasil/internal/contracts"
)

func TestProjectSearchUsesSameBranchWorktreesAndWarnsOnBase(t *testing.T) {
	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("git is not installed")
	}
	parent, err := filepath.EvalSymlinks(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	t.Setenv("YGG_STORAGE_ROOT", filepath.Join(parent, "state"))
	write := func(path, text string) {
		t.Helper()
		if err := os.WriteFile(path, []byte(text), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	bases := map[string]config.ProjectRepo{}
	for _, alias := range []string{"hub", "spoke-a", "spoke-b"} {
		root := filepath.Join(parent, alias)
		if err := os.Mkdir(root, 0o755); err != nil {
			t.Fatal(err)
		}
		cliRunGit(t, root, "init", "-qb", "main")
		cliRunGit(t, root, "config", "user.name", "Ygg Test")
		cliRunGit(t, root, "config", "user.email", "ygg@example.test")
		write(filepath.Join(root, "owner.md"), "decisionneedle "+alias+" base\n")
		write(filepath.Join(root, "second.md"), "budgetneedle "+alias+" second\n")
		cliRunGit(t, root, "add", ".")
		cliRunGit(t, root, "commit", "-qm", "base")
		bases[alias] = config.ProjectRepo{Root: root, Upstream: "origin/main"}
		cliRunGit(t, root, "update-ref", "refs/remotes/origin/main", "HEAD")
	}
	worktrees := map[string]string{}
	for _, alias := range []string{"hub", "spoke-a"} {
		tree := filepath.Join(parent, alias+" rotation worktree")
		cliRunGit(t, bases[alias].Root, "worktree", "add", "-qb", "feature/rotation", tree)
		write(filepath.Join(tree, "owner.md"), "decisionneedle "+alias+" draft\n")
		worktrees[alias] = tree
	}
	spokeBase := bases["spoke-b"].Root
	upstream := filepath.Join(parent, "upstream")
	cliRunGit(t, spokeBase, "worktree", "add", "-q", "--detach", upstream)
	cliRunGit(t, upstream, "commit", "--allow-empty", "-qm", "upstream advance")
	cliRunGit(t, upstream, "update-ref", "refs/remotes/origin/main", "HEAD")
	cliRunGit(t, spokeBase, "worktree", "remove", upstream)
	// A branch without a checkout does not override the base.
	cliRunGit(t, spokeBase, "branch", "feature/rotation")
	write(filepath.Join(spokeBase, "owner.md"), "decisionneedle spoke-b dirty base\n")
	configPath := filepath.Join(parent, "ygg.json")
	raw, err := json.Marshal(map[string]any{
		"schema":   contracts.ConfigSchema,
		"projects": map[string]any{"platform": bases},
	})
	if err != nil {
		t.Fatal(err)
	}
	write(configPath, string(raw))
	t.Setenv("YGG_CONFIG", configPath)

	var stdout, stderr bytes.Buffer
	run := func(args ...string) int {
		t.Helper()
		stdout.Reset()
		stderr.Reset()
		return Main(context.Background(), append([]string{"search", "--mode", "lexical", "--project", "platform"}, args...),
			&stdout, &stderr)
	}
	decode := func() projectResult {
		t.Helper()
		var response struct {
			OK   bool          `json:"ok"`
			Data projectResult `json:"data"`
		}
		if err := json.Unmarshal(stdout.Bytes(), &response); err != nil {
			t.Fatal(err)
		}
		if !response.OK || response.Data.Schema != contracts.ProjectSearchSchema || len(response.Data.Repositories) != 3 {
			t.Fatalf("unexpected response: %s", stdout.String())
		}
		return response.Data
	}

	t.Chdir(worktrees["hub"])
	if code := run("--limit", "3", "-F", "decisionneedle"); code != 0 {
		t.Fatalf("search exit=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
	result := decode()
	if result.Branch != "feature/rotation" {
		t.Fatalf("branch=%q", result.Branch)
	}
	for _, repo := range result.Repositories {
		if len(repo.Result.Records) != 1 || repo.Result.Records[0].Path != "owner.md" {
			t.Fatalf("wrong citation count or path: %+v", repo)
		}
		if expected := worktrees[repo.Alias]; expected != "" {
			if repo.Root != expected || repo.Selection != "branch" || len(repo.Warnings) != 0 ||
				!strings.Contains(repo.Result.Records[0].Excerpt, repo.Alias+" draft") {
				t.Fatalf("wrong branch selection: %+v", repo)
			}
			continue
		}
		if repo.Root != spokeBase || repo.Selection != "base" || len(repo.Warnings) != 2 ||
			repo.Warnings[0].Code != "dirty-base" || repo.Warnings[1].Code != "behind-base" ||
			repo.Warnings[1].Behind != 1 || !strings.Contains(repo.Result.Records[0].Excerpt, "dirty base") {
			t.Fatalf("wrong base selection: %+v", repo)
		}
	}
	if code := run("--limit", "5", "--format", "compact", "-E", "decisionneedle|budgetneedle"); code != 0 ||
		stderr.Len() != 0 || strings.Count(stdout.String(), ".md:") != 5 ||
		!strings.Contains(stdout.String(), worktrees["hub"]) || !strings.Contains(stdout.String(), "warning: base checkout") {
		t.Fatalf("compact exit=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}

	cliRunGit(t, worktrees["hub"], "checkout", "--detach", "-q")
	if code := run("--limit", "3", "-F", "decisionneedle"); code != 0 {
		t.Fatalf("detached search exit=%d stdout=%s", code, stdout.String())
	}
	result = decode()
	if result.Branch != "" {
		t.Fatalf("detached branch=%q", result.Branch)
	}
	for _, repo := range result.Repositories {
		if repo.Root != bases[repo.Alias].Root || repo.Selection != "base" {
			t.Fatalf("detached search selected %+v", repo)
		}
	}
	t.Chdir(parent)
	cliRunGit(t, spokeBase, "update-ref", "-d", "refs/remotes/origin/main")
	if code := run("--limit", "3", "-F", "decisionneedle"); code != 0 {
		t.Fatalf("base search exit=%d stdout=%s", code, stdout.String())
	}
	baseResult := decode().Repositories[2]
	if baseResult.Alias != "spoke-b" || baseResult.Selection != "base" ||
		len(baseResult.Warnings) != 2 || baseResult.Warnings[1].Code != "upstream-unavailable" {
		t.Fatalf("expected unknown upstream warning: %+v", baseResult)
	}
	if code := run("--root", spokeBase, "decisionneedle"); code != 2 {
		t.Fatalf("mixed scopes exit=%d stdout=%s", code, stdout.String())
	}
}
