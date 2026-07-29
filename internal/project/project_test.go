package project

import (
	"os"
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
