package embedding

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
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
