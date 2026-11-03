package rerank

import (
	"bufio"
	"context"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"github.com/coadan/yggdrasil/internal/config"
)

func TestCommandProviderLifecycle(t *testing.T) {
	if os.Getenv("YGG_RERANK_HELPER") == "1" {
		runHelper(false)
		return
	}
	t.Setenv("YGG_RERANK_HELPER", "1")
	provider, err := New(context.Background(), t.TempDir(), config.Rerank{
		Kind: "command", Command: []string{os.Args[0], "-test.run=TestCommandProviderLifecycle"},
		Model: "test", TimeoutMS: 1_000,
	})
	if err != nil {
		t.Fatal(err)
	}
	for range 2 {
		scores, err := provider.Rerank(context.Background(), "needle", []Input{
			{ID: "a", Text: "hay"}, {ID: "b", Text: "needle"},
		})
		if err != nil {
			t.Fatal(err)
		}
		if len(scores) != 2 || scores[0] != (Score{ID: "a", Score: 0}) || scores[1] != (Score{ID: "b", Score: 1}) {
			t.Fatalf("scores=%#v", scores)
		}
	}
	if err := provider.Close(); err != nil {
		t.Fatal(err)
	}
}

func TestCommandProviderReportsWorkerFailure(t *testing.T) {
	if os.Getenv("YGG_RERANK_CRASH_HELPER") == "1" {
		runHelper(true)
		return
	}
	t.Setenv("YGG_RERANK_CRASH_HELPER", "1")
	provider, err := New(context.Background(), t.TempDir(), config.Rerank{
		Kind: "command", Command: []string{os.Args[0], "-test.run=TestCommandProviderReportsWorkerFailure"},
		Model: "test", TimeoutMS: 1_000,
	})
	if err != nil {
		t.Fatal(err)
	}
	_, err = provider.Rerank(context.Background(), "needle", []Input{{ID: "a", Text: "hay"}})
	if err == nil || !strings.Contains(err.Error(), "model unavailable") {
		t.Fatalf("err=%v", err)
	}
	_ = provider.Close()
}

func TestNewRejectsUnsupportedConfiguration(t *testing.T) {
	for _, cfg := range []config.Rerank{
		{Kind: "http", Command: []string{"worker"}},
		{Kind: "command"},
	} {
		if _, err := New(context.Background(), t.TempDir(), cfg); err == nil {
			t.Fatalf("accepted %#v", cfg)
		}
	}
}

func TestBundledLocalWorkerThroughCommandProvider(t *testing.T) {
	python, err := exec.LookPath("python3")
	if err != nil {
		t.Skip("python3 is not installed")
	}
	worker, err := filepath.Abs(filepath.Join("..", "..", "plugins", "rerank-local", "ygg-rerank-local"))
	if err != nil {
		t.Fatal(err)
	}
	t.Setenv("YGG_LOCAL_RERANK_BACKEND", "term-overlap-test")
	provider, err := New(context.Background(), t.TempDir(), config.Rerank{
		Kind: "command", Command: []string{python, worker}, Model: "protocol-fixture", TimeoutMS: 5_000,
	})
	if err != nil {
		t.Fatal(err)
	}
	scores, err := provider.Rerank(context.Background(), "retry budget", []Input{
		{ID: "x", Text: "unrelated"}, {ID: "y", Text: "retry budget"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(scores) != 2 || scores[0] != (Score{ID: "x", Score: 0}) || scores[1] != (Score{ID: "y", Score: 2}) {
		t.Fatalf("scores=%#v", scores)
	}
	if err := provider.Close(); err != nil {
		t.Fatal(err)
	}
}

func runHelper(crash bool) {
	scanner := bufio.NewScanner(os.Stdin)
	encoder := json.NewEncoder(os.Stdout)
	for scanner.Scan() {
		var message struct {
			Type      string  `json:"type"`
			Schema    string  `json:"schema"`
			RequestID string  `json:"requestId"`
			Query     string  `json:"query"`
			Inputs    []Input `json:"inputs"`
		}
		if err := json.Unmarshal(scanner.Bytes(), &message); err != nil {
			os.Exit(2)
		}
		switch message.Type {
		case "hello":
			if message.Schema != "ygg.rerank/v1" {
				os.Exit(2)
			}
			_ = encoder.Encode(map[string]any{"type": "ready", "schema": "ygg.rerank/v1"})
		case "rerank":
			if crash {
				os.Stderr.WriteString("loading\nmodel unavailable\n")
				os.Exit(3)
			}
			scores := make([]Score, len(message.Inputs))
			for i, input := range message.Inputs {
				scores[i] = Score{ID: input.ID}
				if strings.Contains(input.Text, message.Query) {
					scores[i].Score = 1
				}
			}
			_ = encoder.Encode(map[string]any{
				"type": "result", "requestId": message.RequestID, "scores": scores,
			})
		case "end":
			_ = encoder.Encode(map[string]any{"type": "summary"})
			return
		}
	}
}
