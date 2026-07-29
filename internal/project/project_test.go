package project

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func TestResolveUsesCentralStableStatePath(t *testing.T) {
	root := t.TempDir()
	storage := t.TempDir()
	t.Setenv("YGG_STORAGE_ROOT", storage)
	paths, err := Resolve(root)
	if err != nil {
		t.Fatal(err)
	}
	wantRoot, err := filepath.EvalSymlinks(root)
	if err != nil {
		t.Fatal(err)
	}
	if paths.Root != wantRoot {
		t.Fatalf("root = %q, want %q", paths.Root, wantRoot)
	}
	if len(paths.ID) != 24 {
		t.Fatalf("id length = %d", len(paths.ID))
	}
	if !strings.HasPrefix(paths.Database, filepath.Join(storage, "indexes")) {
		t.Fatalf("database outside storage root: %s", paths.Database)
	}
	if _, err := os.Stat(paths.StateDir); !os.IsNotExist(err) {
		t.Fatalf("Resolve should not create state, stat err = %v", err)
	}
}

func TestResolveCanonicalizesRepositorySubdirectory(t *testing.T) {
	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("git is not installed")
	}
	root := t.TempDir()
	nested := filepath.Join(root, "tests", "browser")
	if err := os.MkdirAll(nested, 0o755); err != nil {
		t.Fatal(err)
	}
	runGit(t, root, "init", "-q")
	t.Setenv("YGG_STORAGE_ROOT", t.TempDir())

	rootPaths, err := Resolve(root)
	if err != nil {
		t.Fatal(err)
	}
	nestedPaths, err := Resolve(nested)
	if err != nil {
		t.Fatal(err)
	}
	if nestedPaths.Root != rootPaths.Root {
		t.Fatalf("nested root = %q, want %q", nestedPaths.Root, rootPaths.Root)
	}
	if nestedPaths.Scope != "tests/browser/" {
		t.Fatalf("nested scope = %q, want tests/browser/", nestedPaths.Scope)
	}
	if nestedPaths.ID != rootPaths.ID || nestedPaths.Database != rootPaths.Database {
		t.Fatalf("nested paths = %#v, want repository identity %#v", nestedPaths, rootPaths)
	}
}

func TestResolveCanonicalizesRepositoryFileScope(t *testing.T) {
	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("git is not installed")
	}
	root := t.TempDir()
	file := filepath.Join(root, "src", "owner.ts")
	if err := os.MkdirAll(filepath.Dir(file), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(file, []byte("owner\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	runGit(t, root, "init", "-q")
	t.Setenv("YGG_STORAGE_ROOT", t.TempDir())

	rootPaths, err := Resolve(root)
	if err != nil {
		t.Fatal(err)
	}
	filePaths, err := Resolve(file)
	if err != nil {
		t.Fatal(err)
	}
	if filePaths.Root != rootPaths.Root ||
		filePaths.ID != rootPaths.ID ||
		filePaths.Database != rootPaths.Database {
		t.Fatalf("file paths = %#v, want repository identity %#v", filePaths, rootPaths)
	}
	if filePaths.Scope != "src/owner.ts" {
		t.Fatalf("file scope = %q, want src/owner.ts", filePaths.Scope)
	}
}

func runGit(t *testing.T, root string, args ...string) {
	t.Helper()
	command := exec.Command("git", append([]string{"-C", root}, args...)...)
	if output, err := command.CombinedOutput(); err != nil {
		t.Fatalf("git %s: %v: %s", strings.Join(args, " "), err, output)
	}
}
