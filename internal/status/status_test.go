package status

import (
	"context"
	"os"
	"path/filepath"
	"testing"

	"github.com/coadan/yggdrasil/internal/config"
	"github.com/coadan/yggdrasil/internal/indexer"
	"github.com/coadan/yggdrasil/internal/project"
)

func TestInspectDoesNotReportDeliberatelySkippedFilesAsDrift(t *testing.T) {
	root := t.TempDir()
	t.Setenv("YGG_STORAGE_ROOT", t.TempDir())
	if err := os.WriteFile(filepath.Join(root, "text.txt"), []byte("searchable"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "binary.dat"), []byte{0, 1, 2}, 0o644); err != nil {
		t.Fatal(err)
	}
	paths, err := project.Resolve(root)
	if err != nil {
		t.Fatal(err)
	}
	cfg := config.Default()
	summary, err := indexer.Run(context.Background(), paths, cfg, indexer.Options{})
	if err != nil || summary.Indexed != 1 || summary.Skipped != 1 {
		t.Fatalf("summary=%#v err=%v", summary, err)
	}
	result, err := Inspect(context.Background(), paths, cfg)
	if err != nil {
		t.Fatal(err)
	}
	if result.Freshness.New != 0 ||
		result.Freshness.Modified != 0 ||
		result.Freshness.Deleted != 0 ||
		result.Freshness.Unchanged != 1 {
		t.Fatalf("freshness=%#v", result.Freshness)
	}
}
