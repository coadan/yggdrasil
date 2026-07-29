package plugin

import (
	"bufio"
	"context"
	"encoding/json"
	"os"
	"testing"

	"github.com/coadan/yggdrasil/internal/config"
	"github.com/coadan/yggdrasil/internal/contracts"
	"github.com/coadan/yggdrasil/internal/discovery"
)

func TestSessionExtractsAndStampsRecords(t *testing.T) {
	if os.Getenv("YGG_PLUGIN_HELPER") == "1" {
		runHelper()
		return
	}
	t.Setenv("YGG_PLUGIN_HELPER", "1")
	cfg := config.Plugin{
		ID: "helper", Version: "1", Command: []string{os.Args[0], "-test.run=TestSessionExtractsAndStampsRecords"},
		IncludeGlobs: []string{"**/*.md"}, TimeoutMS: 1_000,
	}
	session, err := Start(context.Background(), t.TempDir(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	records, diagnostics, err := session.Extract(context.Background(), discovery.File{
		Candidate: discovery.Candidate{Path: "README.md"},
		Kind:      "md",
		Content:   "# Hello\n",
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(diagnostics) != 0 || len(records) != 1 || records[0].Source != "plugin:helper" {
		t.Fatalf("records=%#v diagnostics=%#v", records, diagnostics)
	}
	if err := session.Close(); err != nil {
		t.Fatal(err)
	}
}

func runHelper() {
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
					"id": "heading", "startLine": 1, "endLine": 1,
					"kind": "markdown-section", "title": "Hello", "text": "# Hello",
				}},
				"diagnostics": []any{},
			})
		case "end":
			_ = encoder.Encode(map[string]any{"type": "summary", "files": 1, "records": 1})
			return
		}
	}
}

func TestValidateRecordsRejectsProtectedSource(t *testing.T) {
	_, err := validateRecords(config.Plugin{ID: "bad"}, discovery.File{
		Candidate: discovery.Candidate{Path: "a.md"},
		Content:   "text",
	}, []contracts.SearchRecord{{
		Path: "a.md", StartLine: 1, EndLine: 1, Kind: "x", Text: "x", Source: "spoofed",
	}})
	if err == nil {
		t.Fatal("expected protected source error")
	}
}
