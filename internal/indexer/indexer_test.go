package indexer

import (
	"bufio"
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/coadan/yggdrasil/internal/config"
	"github.com/coadan/yggdrasil/internal/project"
	"github.com/coadan/yggdrasil/internal/search"
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

func TestRunAddsConfiguredPluginRecords(t *testing.T) {
	if os.Getenv("YGG_INDEXER_PLUGIN_HELPER") == "1" {
		runIndexerPluginHelper()
		return
	}
	root := t.TempDir()
	t.Setenv("YGG_STORAGE_ROOT", t.TempDir())
	t.Setenv("YGG_INDEXER_PLUGIN_HELPER", "1")
	if err := os.WriteFile(filepath.Join(root, "README.md"), []byte("# Heading\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	paths, err := project.Resolve(root)
	if err != nil {
		t.Fatal(err)
	}
	cfg := config.Default()
	cfg.Plugins = []config.Plugin{{
		ID: "helper", Version: "1",
		Command:      []string{os.Args[0], "-test.run=TestRunAddsConfiguredPluginRecords"},
		IncludeGlobs: []string{"**/*.md"}, TimeoutMS: 1_000,
	}}
	if _, err := Run(context.Background(), paths, cfg, Options{}); err != nil {
		t.Fatal(err)
	}
	value, err := store.Open(context.Background(), paths.Database, paths.Root, paths.ID)
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	result, err := search.Run(context.Background(), value, "pluginunique", search.Options{Mode: "lexical"})
	if err != nil {
		t.Fatal(err)
	}
	if len(result.Records) == 0 || result.Records[0].Source != "plugin:helper" {
		t.Fatalf("plugin record missing: %#v", result.Records)
	}
}

func runIndexerPluginHelper() {
	scanner := bufio.NewScanner(os.Stdin)
	encoder := json.NewEncoder(os.Stdout)
	for scanner.Scan() {
		var message map[string]any
		if err := json.Unmarshal(scanner.Bytes(), &message); err != nil {
			os.Exit(2)
		}
		switch message["type"] {
		case "hello":
			_ = encoder.Encode(map[string]any{"type": "ready", "schema": "ygg.extractor/v1"})
		case "file":
			_ = encoder.Encode(map[string]any{
				"type": "result", "requestId": message["requestId"],
				"records": []map[string]any{{
					"id": "record", "startLine": 1, "endLine": 1,
					"kind": "test", "text": "pluginunique",
				}},
			})
		case "end":
			_ = encoder.Encode(map[string]any{"type": "summary", "files": 1, "records": 1})
			return
		}
	}
}
