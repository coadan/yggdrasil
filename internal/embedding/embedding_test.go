package embedding

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
	"sync/atomic"
	"testing"
	"time"

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

func TestFingerprintSeparatesVectorIdentityFromRuntimeTuning(t *testing.T) {
	base := config.Embedding{
		Kind: "openai-compatible", Endpoint: "http://localhost", Model: "model",
		Dimensions: 384, TimeoutMS: 1_000, BatchSize: 8, MaxInputChars: 4_000,
	}
	tuned := base
	tuned.TimeoutMS = 120_000
	tuned.BatchSize = 1
	tuned.APIKeyEnv = "OTHER_KEY"
	tuned.QueryPrefix = "Instruct: code search\nQuery: "
	if Fingerprint(base) != Fingerprint(tuned) {
		t.Fatal("runtime tuning or query shaping invalidated stored document vectors")
	}
	tuned.DocumentPrefix = "Document: "
	if Fingerprint(base) == Fingerprint(tuned) {
		t.Fatal("document shaping did not invalidate stored vectors")
	}
	tuned = base
	tuned.Model = "another-model"
	if Fingerprint(base) == Fingerprint(tuned) {
		t.Fatal("model change did not invalidate stored vectors")
	}
}

func TestEmbeddingInputPrefixesArePurposeSpecific(t *testing.T) {
	cfg := config.Embedding{QueryPrefix: "query: ", DocumentPrefix: "document: "}
	if got := QueryText(cfg, "owner"); got != "query: owner" {
		t.Fatalf("query text = %q", got)
	}
	if got := DocumentText(cfg, "owner"); got != "document: owner" {
		t.Fatalf("document text = %q", got)
	}
}

func TestHTTPProviderBoundsInputAndRetriesTransientStatus(t *testing.T) {
	var requests atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		var body struct {
			Input          []string `json:"input"`
			EncodingFormat string   `json:"encoding_format"`
		}
		if err := json.NewDecoder(request.Body).Decode(&body); err != nil {
			t.Error(err)
			return
		}
		if len([]rune(body.Input[0])) != defaultMaxProviderInputChars {
			t.Errorf("input chars=%d", len([]rune(body.Input[0])))
		}
		if body.EncodingFormat != "float" {
			t.Errorf("encoding_format=%q", body.EncodingFormat)
		}
		if requests.Add(1) == 1 {
			writer.WriteHeader(http.StatusTooManyRequests)
			_, _ = writer.Write([]byte(`{"error":{"message":"retry"}}`))
			return
		}
		_ = json.NewEncoder(writer).Encode(map[string]any{
			"data": []map[string]any{{"index": 0, "embedding": []float32{1, 0}}},
		})
	}))
	defer server.Close()
	provider := &httpProvider{
		cfg: config.Embedding{
			Kind: "openai-compatible", Endpoint: server.URL, Model: "test",
			Dimensions: 2, TimeoutMS: 1_000,
		},
		client:       server.Client(),
		maxRetries:   1,
		retryBackoff: time.Millisecond,
	}
	values, err := provider.Embed(context.Background(), []Input{{
		ID: "unicode", Text: strings.Repeat("ø", defaultMaxProviderInputChars+10),
	}})
	if err != nil {
		t.Fatal(err)
	}
	if requests.Load() != 2 || len(values) != 1 {
		t.Fatalf("requests=%d values=%#v", requests.Load(), values)
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

func TestCommandProviderBoundsInput(t *testing.T) {
	if os.Getenv("YGG_EMBEDDING_BOUND_HELPER") == "1" {
		runCommandProviderBoundHelper()
		return
	}
	t.Setenv("YGG_EMBEDDING_BOUND_HELPER", "1")
	provider, err := New(context.Background(), t.TempDir(), config.Embedding{
		Kind: "command", Command: []string{os.Args[0], "-test.run=TestCommandProviderBoundsInput"},
		Model: "test", Dimensions: 2, TimeoutMS: 1_000,
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := provider.Embed(context.Background(), []Input{
		{ID: "a", Text: strings.Repeat("é", defaultMaxProviderInputChars+10)},
	}); err != nil {
		t.Fatal(err)
	}
	if err := provider.Close(); err != nil {
		t.Fatal(err)
	}
}

func TestBundledLocalWorkerThroughCommandProvider(t *testing.T) {
	python, err := exec.LookPath("python3")
	if err != nil {
		t.Skip("python3 is not installed")
	}
	worker, err := filepath.Abs(filepath.Join("..", "..", "plugins", "embedding-local", "ygg-embed-local"))
	if err != nil {
		t.Fatal(err)
	}
	t.Setenv("YGG_LOCAL_EMBEDDING_BACKEND", "deterministic-test")
	provider, err := New(context.Background(), t.TempDir(), config.Embedding{
		Kind: "command", Command: []string{python, worker},
		Model: "protocol-fixture", Dimensions: 8, TimeoutMS: 1_000,
	})
	if err != nil {
		t.Fatal(err)
	}
	values, err := provider.Embed(context.Background(), []Input{
		{ID: "b", Text: "second"}, {ID: "a", Text: "first"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(values) != 2 || values[0].ID != "b" || values[1].ID != "a" {
		t.Fatalf("values=%#v", values)
	}
	for _, value := range values {
		var squaredMagnitude float64
		for _, coordinate := range value.Vector {
			squaredMagnitude += float64(coordinate * coordinate)
		}
		if squaredMagnitude < 0.999 || squaredMagnitude > 1.001 {
			t.Fatalf("embedding %q is not normalized: squared magnitude %f", value.ID, squaredMagnitude)
		}
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

func runCommandProviderBoundHelper() {
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
			if len(message.Inputs) != 1 || len([]rune(message.Inputs[0].Text)) != defaultMaxProviderInputChars {
				os.Exit(3)
			}
			_ = encoder.Encode(map[string]any{
				"type": "result", "requestId": message.RequestID,
				"values": []Value{{ID: message.Inputs[0].ID, Vector: []float32{1, 0}}},
			})
		case "end":
			_ = encoder.Encode(map[string]any{"type": "summary"})
			return
		}
	}
}

func TestHTTPProviderIsolatesAndShortensRejectedInput(t *testing.T) {
	const accepted = 500
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var request struct {
			Input []string `json:"input"`
		}
		if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
			t.Error(err)
		}
		type item struct {
			Index     int       `json:"index"`
			Embedding []float32 `json:"embedding"`
		}
		data := make([]item, len(request.Input))
		for index, text := range request.Input {
			if len(text) > accepted {
				http.Error(w, "input too dense", http.StatusBadRequest)
				return
			}
			data[index] = item{Index: index, Embedding: []float32{float32(len(text))}}
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"data": data})
	}))
	defer server.Close()
	provider := &httpProvider{
		cfg:    config.Embedding{Model: "m", Dimensions: 1, Endpoint: server.URL, MaxInputChars: 1000},
		client: server.Client(),
	}
	values, err := provider.Embed(context.Background(), []Input{
		{ID: "first", Text: "short"},
		{ID: "dense", Text: strings.Repeat("x", 1000)},
		{ID: "last", Text: "tiny"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(values) != 3 || values[0].ID != "first" || values[1].ID != "dense" || values[2].ID != "last" {
		t.Fatalf("values=%#v", values)
	}
	if values[1].Vector[0] != accepted {
		t.Fatalf("dense input length=%v", values[1].Vector[0])
	}
}

func TestHTTPProviderBoundsRejectedInputRecovery(t *testing.T) {
	var requests atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		http.Error(w, "bad request", http.StatusBadRequest)
	}))
	defer server.Close()
	provider := &httpProvider{
		cfg:    config.Embedding{Model: "m", Dimensions: 1, Endpoint: server.URL, MaxInputChars: 4000},
		client: server.Client(),
	}
	_, err := provider.Embed(context.Background(), []Input{{ID: "a", Text: strings.Repeat("x", 4000)}})
	if err == nil || !strings.Contains(err.Error(), "HTTP 400") {
		t.Fatalf("err=%v", err)
	}
	if requests.Load() != 4 {
		t.Fatalf("requests=%d", requests.Load())
	}
}

func TestHTTPProviderDoesNotShortenOnOtherFailures(t *testing.T) {
	var lengths []int
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var request struct {
			Input []string `json:"input"`
		}
		_ = json.NewDecoder(r.Body).Decode(&request)
		lengths = append(lengths, len(request.Input[0]))
		http.Error(w, "unauthorized", http.StatusUnauthorized)
	}))
	defer server.Close()
	provider := &httpProvider{
		cfg:    config.Embedding{Model: "m", Dimensions: 1, Endpoint: server.URL, MaxInputChars: 4000},
		client: server.Client(),
	}
	if _, err := provider.Embed(context.Background(), []Input{{ID: "a", Text: strings.Repeat("x", 2000)}}); err == nil {
		t.Fatal("expected failure")
	}
	if len(lengths) != 1 || lengths[0] != 2000 {
		t.Fatalf("lengths=%v", lengths)
	}
}
