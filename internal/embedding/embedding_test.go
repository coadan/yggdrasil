package embedding

import (
	"bufio"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"

	"github.com/coadan/yggdrasil/internal/config"
)

func TestHTTPProviderPreservesInputIDs(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		var body struct {
			Input []string `json:"input"`
		}
		if err := json.NewDecoder(request.Body).Decode(&body); err != nil {
			t.Error(err)
			return
		}
		_ = json.NewEncoder(writer).Encode(map[string]any{
			"data": []map[string]any{
				{"index": 1, "embedding": []float32{0, 1}},
				{"index": 0, "embedding": []float32{1, 0}},
			},
		})
	}))
	defer server.Close()
	provider, err := New(context.Background(), t.TempDir(), config.Embedding{
		Kind: "openai-compatible", Endpoint: server.URL, Model: "test",
		Dimensions: 2, TimeoutMS: 1_000,
	})
	if err != nil {
		t.Fatal(err)
	}
	values, err := provider.Embed(context.Background(), []Input{
		{ID: "a", Text: "first"}, {ID: "b", Text: "second"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if values[0].ID != "a" || values[0].Vector[0] != 1 || values[1].ID != "b" {
		t.Fatalf("values=%#v", values)
	}
}

func TestHTTPProviderRejectsWrongDimensions(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		_ = json.NewEncoder(writer).Encode(map[string]any{
			"data": []map[string]any{{"index": 0, "embedding": []float32{1}}},
		})
	}))
	defer server.Close()
	provider, err := New(context.Background(), t.TempDir(), config.Embedding{
		Kind: "openai-compatible", Endpoint: server.URL, Model: "test",
		Dimensions: 2, TimeoutMS: 1_000,
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := provider.Embed(context.Background(), []Input{{ID: "a", Text: "first"}}); err == nil {
		t.Fatal("expected dimension error")
	}
}

func TestHTTPProviderBoundsErrorBody(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		writer.WriteHeader(http.StatusBadGateway)
		_, _ = writer.Write([]byte(strings.Repeat("x", maxProviderError*2)))
	}))
	defer server.Close()
	provider, err := New(context.Background(), t.TempDir(), config.Embedding{
		Kind: "openai-compatible", Endpoint: server.URL, Model: "test",
		Dimensions: 2, TimeoutMS: 1_000,
	})
	if err != nil {
		t.Fatal(err)
	}
	_, err = provider.Embed(context.Background(), []Input{{ID: "a", Text: "first"}})
	if err == nil || len(err.Error()) > maxProviderError+100 {
		t.Fatalf("unbounded or missing error: length=%d err=%v", len(err.Error()), err)
	}
}

func TestCommandProviderLifecycle(t *testing.T) {
	if os.Getenv("YGG_EMBEDDING_HELPER") == "1" {
		runCommandProviderHelper()
		return
	}
	t.Setenv("YGG_EMBEDDING_HELPER", "1")
	provider, err := New(context.Background(), t.TempDir(), config.Embedding{
		Kind: "command", Command: []string{os.Args[0], "-test.run=TestCommandProviderLifecycle"},
		Model: "test", Dimensions: 2, TimeoutMS: 1_000,
	})
	if err != nil {
		t.Fatal(err)
	}
	values, err := provider.Embed(context.Background(), []Input{
		{ID: "a", Text: "first"}, {ID: "b", Text: "second"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(values) != 2 || values[0].ID != "a" || values[1].ID != "b" {
		t.Fatalf("values=%#v", values)
	}
	if err := provider.Close(); err != nil {
		t.Fatal(err)
	}
}

func runCommandProviderHelper() {
	scanner := bufio.NewScanner(os.Stdin)
	encoder := json.NewEncoder(os.Stdout)
	for scanner.Scan() {
		var message struct {
			Type      string  `json:"type"`
			RequestID string  `json:"requestId"`
			Inputs    []Input `json:"inputs"`
		}
		if err := json.Unmarshal(scanner.Bytes(), &message); err != nil {
			os.Exit(2)
		}
		switch message.Type {
		case "hello":
			_ = encoder.Encode(map[string]any{"type": "ready", "schema": "ygg.embedding/v1"})
		case "embed":
			values := make([]Value, len(message.Inputs))
			for i, input := range message.Inputs {
				values[i] = Value{ID: input.ID, Vector: []float32{1, 0}}
			}
			_ = encoder.Encode(map[string]any{
				"type": "result", "requestId": message.RequestID, "values": values,
			})
		case "end":
			_ = encoder.Encode(map[string]any{"type": "summary"})
			return
		}
	}
}
