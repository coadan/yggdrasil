package rerank

import (
	"bufio"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
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
		{Kind: "grpc", Command: []string{"worker"}},
		{Kind: "http"},
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

func TestHTTPProviderMapsResultIndexesToInputIDs(t *testing.T) {
	var request struct {
		Model     string   `json:"model"`
		Query     string   `json:"query"`
		Documents []string `json:"documents"`
		TopN      int      `json:"top_n"`
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
			t.Error(err)
		}
		_, _ = w.Write([]byte(`{"results":[{"index":1,"relevance_score":0.9},{"index":0,"relevance_score":-2.5}]}`))
	}))
	defer server.Close()
	provider, err := New(context.Background(), t.TempDir(), config.Rerank{
		Kind: "http", Endpoint: server.URL, Model: "fixture", TimeoutMS: 1_000,
	})
	if err != nil {
		t.Fatal(err)
	}
	scores, err := provider.Rerank(context.Background(), "needle", []Input{
		{ID: "a", Text: "hay"}, {ID: "b", Text: "needle"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(scores) != 2 || scores[0] != (Score{ID: "b", Score: 0.9}) || scores[1] != (Score{ID: "a", Score: -2.5}) {
		t.Fatalf("scores=%#v", scores)
	}
	if request.Model != "fixture" || request.Query != "needle" || request.TopN != 2 ||
		strings.Join(request.Documents, ",") != "hay,needle" {
		t.Fatalf("request=%#v", request)
	}
	if err := provider.Close(); err != nil {
		t.Fatal(err)
	}
}

func TestHTTPProviderRejectsFailuresAndMalformedResults(t *testing.T) {
	for _, item := range []struct {
		status int
		body   string
		want   string
	}{
		{http.StatusServiceUnavailable, strings.Repeat("x", 3*maxProviderError), "503"},
		{http.StatusOK, `{"results":[{"index":2,"relevance_score":1}]}`, "out of range"},
		{http.StatusOK, `{"results":[{"index":0}]}`, "lacks index"},
		{http.StatusOK, `not json`, "decode"},
	} {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(item.status)
			_, _ = w.Write([]byte(item.body))
		}))
		provider, err := New(context.Background(), t.TempDir(), config.Rerank{
			Kind: "http", Endpoint: server.URL, TimeoutMS: 1_000,
		})
		if err != nil {
			t.Fatal(err)
		}
		_, err = provider.Rerank(context.Background(), "q", []Input{{ID: "a", Text: "x"}, {ID: "b", Text: "y"}})
		server.Close()
		if err == nil || !strings.Contains(err.Error(), item.want) || len(err.Error()) > 2*maxProviderError {
			t.Fatalf("status %d: err=%v", item.status, err)
		}
	}
}
