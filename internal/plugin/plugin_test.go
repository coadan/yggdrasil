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
			records := []map[string]any{{
				"id": "heading", "startLine": 1, "endLine": 1,
				"kind": "markdown-section", "title": "Hello", "text": "# Hello",
			}}
			if os.Getenv("YGG_PLUGIN_DUPLICATE") == "1" {
				records = append(records, records[0])
			}
			_ = encoder.Encode(map[string]any{
				"type": "result", "requestId": message["requestId"],
				"records":     records,
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

func TestValidateRecordsRejectsDuplicatePluginIDs(t *testing.T) {
	file := discovery.File{
		Candidate: discovery.Candidate{Path: "a.md"},
		Content:   "first\nsecond",
	}
	records := []contracts.SearchRecord{
		{ID: "same", StartLine: 1, EndLine: 1, Kind: "section", Text: "first"},
		{ID: "same", StartLine: 2, EndLine: 2, Kind: "section", Text: "second"},
	}
	if _, err := validateRecords(config.Plugin{ID: "bad"}, file, records); err == nil {
		t.Fatal("expected duplicate plugin record id error")
	}
}

func TestManagerIsolatesDuplicatePluginIDs(t *testing.T) {
	if os.Getenv("YGG_PLUGIN_HELPER") == "1" {
		runHelper()
		return
	}
	t.Setenv("YGG_PLUGIN_HELPER", "1")
	t.Setenv("YGG_PLUGIN_DUPLICATE", "1")
	cfg := config.Plugin{
		ID: "helper", Version: "1",
		Command:      []string{os.Args[0], "-test.run=TestManagerIsolatesDuplicatePluginIDs"},
		IncludeGlobs: []string{"**/*.md"}, TimeoutMS: 1_000,
	}
	manager := NewManager(context.Background(), t.TempDir(), []config.Plugin{cfg})
	records, diagnostics := manager.Extract(discovery.File{
		Candidate: discovery.Candidate{Path: "README.md"},
		Kind:      "md",
		Content:   "# Hello\n",
	})
	if len(records) != 0 || len(diagnostics) != 1 {
		t.Fatalf("records=%#v diagnostics=%#v", records, diagnostics)
	}
	if diagnostics[0].Stage != "extract" {
		t.Fatalf("diagnostic=%#v", diagnostics[0])
	}
	if closeDiagnostics := manager.Close(); len(closeDiagnostics) != 0 {
		t.Fatalf("close diagnostics=%#v", closeDiagnostics)
	}
}
