package indexer

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/coadan/yggdrasil/internal/config"
	"github.com/coadan/yggdrasil/internal/project"
	"github.com/coadan/yggdrasil/internal/store"
)

func TestRunIsIncrementalAndDeletesMissingFiles(t *testing.T) {
	root := t.TempDir()
	storage := t.TempDir()
	t.Setenv("YGG_STORAGE_ROOT", storage)
	file := filepath.Join(root, "a.txt")
	if err := os.WriteFile(file, []byte("hello"), 0o644); err != nil {
		t.Fatal(err)
	}
	paths, err := project.Resolve(root)
	if err != nil {
		t.Fatal(err)
	}
	first, err := Run(context.Background(), paths, config.Default(), Options{})
	if err != nil || first.Indexed != 1 {
		t.Fatalf("first=%#v err=%v", first, err)
	}
	second, err := Run(context.Background(), paths, config.Default(), Options{})
	if err != nil || second.Unchanged != 1 || second.Indexed != 0 {
		t.Fatalf("second=%#v err=%v", second, err)
	}
	time.Sleep(time.Millisecond)
	if err := os.WriteFile(file, []byte("changed"), 0o644); err != nil {
		t.Fatal(err)
	}
	third, err := Run(context.Background(), paths, config.Default(), Options{})
	if err != nil || third.Indexed != 1 {
		t.Fatalf("third=%#v err=%v", third, err)
	}
	if err := os.Remove(file); err != nil {
		t.Fatal(err)
	}
	fourth, err := Run(context.Background(), paths, config.Default(), Options{})
	if err != nil || fourth.Deleted != 1 {
		t.Fatalf("fourth=%#v err=%v", fourth, err)
	}
	value, err := store.Open(context.Background(), paths.Database, paths.Root, paths.ID)
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	counts, err := value.Counts(context.Background())
	if err != nil || counts.Files != 0 {
		t.Fatalf("counts=%#v err=%v", counts, err)
	}
}
