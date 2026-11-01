package command

import (
	"bufio"
	"context"
	"encoding/json"
	"os"
	"testing"

	"github.com/coadan/yggdrasil/extractor"
)

func TestProviderLazilyRunsCommandAdapter(t *testing.T) {
	if os.Getenv("YGG_COMMAND_EXTRACTOR_HELPER") == "1" {
		runHelper()
		return
	}
	t.Setenv("YGG_COMMAND_EXTRACTOR_HELPER", "1")
	provider, err := New(context.Background(), t.TempDir(), Spec{
		ID: "helper", Version: "1",
		Command:      []string{os.Args[0], "-test.run=TestProviderLazilyRunsCommandAdapter"},
		IncludeGlobs: []string{"**/*.md"}, TimeoutMS: 1_000,
	})
	if err != nil {
		t.Fatal(err)
	}
	if provider.Descriptor().Fingerprint == "" {
		t.Fatal("missing provider fingerprint")
	}
	records, diagnostics, err := provider.Extract(context.Background(), extractor.File{
		Path: "README.md", Kind: "md", Content: "# Hello\n",
	})
	if err == nil {
		records, err = extractor.NormalizeRecords(provider.Descriptor(), extractor.File{
			Path: "README.md", Kind: "md", Content: "# Hello\n",
		}, records)
	}
	if err != nil || len(diagnostics) != 0 || len(records) != 1 ||
		records[0].Source != "plugin:helper" {
		t.Fatalf("records=%#v diagnostics=%#v err=%v", records, diagnostics, err)
	}
	if diagnostics := provider.Close(); len(diagnostics) != 0 {
		t.Fatalf("close diagnostics=%#v", diagnostics)
	}
}

func runHelper() {
	scanner := bufio.NewScanner(os.Stdin)
	encoder := json.NewEncoder(os.Stdout)
	for scanner.Scan() {
		var message map[string]any
		if json.Unmarshal(scanner.Bytes(), &message) != nil {
			os.Exit(2)
		}
		switch message["type"] {
		case "hello":
			_ = encoder.Encode(map[string]any{"type": "ready", "schema": "ygg.extractor/v1"})
		case "file":
			_ = encoder.Encode(map[string]any{
				"type": "result", "requestId": message["requestId"],
				"records": []map[string]any{{
					"id": "heading", "startLine": 1, "endLine": 1,
					"kind": "section", "text": "Hello",
				}},
			})
		case "end":
			_ = encoder.Encode(map[string]any{"type": "summary"})
			return
		}
	}
}
