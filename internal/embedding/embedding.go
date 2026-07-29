// Package embedding owns bounded command and OpenAI-compatible embedding
// providers without provider SDK dependencies.
package embedding

import (
	"bufio"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"strings"
	"sync"
	"time"

	"github.com/coadan/yggdrasil/internal/config"
	"github.com/coadan/yggdrasil/internal/contracts"
)

const maxProviderResponse = 16 * 1024 * 1024
const maxProviderStderr = 1024 * 1024
const maxProviderError = 8 * 1024
const defaultMaxProviderInputChars = 6000
const embeddingBehaviorVersion = "v2"

type Input struct {
	ID   string `json:"id"`
	Text string `json:"text"`
}

type Value struct {
	ID     string    `json:"id"`
	Vector []float32 `json:"vector"`
}

type Provider interface {
	Embed(context.Context, []Input) ([]Value, error)
	Close() error
}

func New(ctx context.Context, root string, cfg config.Embedding) (Provider, error) {
	switch cfg.Kind {
	case "command":
		if len(cfg.Command) == 0 || cfg.Command[0] == "" {
			return nil, errors.New("embedding command is required")
		}
		return startCommand(ctx, root, cfg)
	case "openai-compatible":
		return &httpProvider{
			cfg: cfg,
			client: &http.Client{
				Timeout: time.Duration(cfg.TimeoutMS) * time.Millisecond,
			},
			maxRetries:   1,
			retryBackoff: 500 * time.Millisecond,
		}, nil
	default:
		return nil, fmt.Errorf("unsupported embedding kind %q", cfg.Kind)
	}
}

func Fingerprint(cfg config.Embedding) string {
	data, _ := json.Marshal(struct {
		Behavior string           `json:"behavior"`
		Config   config.Embedding `json:"config"`
	}{Behavior: embeddingBehaviorVersion, Config: cfg})
	sum := sha256.Sum256(data)
	return "sha256:" + hex.EncodeToString(sum[:])
}

type httpProvider struct {
	cfg          config.Embedding
	client       *http.Client
	maxRetries   int
	retryBackoff time.Duration
}

func (p *httpProvider) Embed(ctx context.Context, inputs []Input) ([]Value, error) {
	texts := make([]string, len(inputs))
	for i := range inputs {
		texts[i] = truncateChars(inputs[i].Text, providerInputLimit(p.cfg))
	}
	payload, err := json.Marshal(map[string]any{
		"model": p.cfg.Model, "input": texts, "encoding_format": "float",
	})
	if err != nil {
		return nil, err
	}
	for attempt := 0; ; attempt++ {
		values, status, err := p.embedOnce(ctx, inputs, payload)
		if err == nil {
			return values, nil
		}
		retryable := status == 0 || status == 429 || status == 529 || status >= 500
		if !retryable || attempt >= p.maxRetries || ctx.Err() != nil {
			return nil, err
		}
		timer := time.NewTimer(p.retryBackoff * time.Duration(1<<attempt))
		select {
		case <-ctx.Done():
			timer.Stop()
			return nil, ctx.Err()
		case <-timer.C:
		}
	}
}

func (p *httpProvider) embedOnce(ctx context.Context, inputs []Input, payload []byte) ([]Value, int, error) {
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, p.cfg.Endpoint, bytes.NewReader(payload))
	if err != nil {
		return nil, 0, err
	}
	request.Header.Set("Content-Type", "application/json")
	if p.cfg.APIKeyEnv != "" {
		key := os.Getenv(p.cfg.APIKeyEnv)
		if key == "" {
			return nil, http.StatusUnauthorized, fmt.Errorf("embedding API key environment %s is empty", p.cfg.APIKeyEnv)
		}
		request.Header.Set("Authorization", "Bearer "+key)
	}
	response, err := p.client.Do(request)
	if err != nil {
		return nil, 0, err
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, maxProviderResponse+1))
	closeErr := response.Body.Close()
	if err != nil {
		return nil, response.StatusCode, err
	}
	if closeErr != nil {
		return nil, response.StatusCode, closeErr
	}
	if len(body) > maxProviderResponse {
		return nil, response.StatusCode, errors.New("embedding response exceeds 16 MiB")
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		detail := body
		suffix := ""
		if len(detail) > maxProviderError {
			detail = detail[:maxProviderError]
			suffix = "…"
		}
		return nil, response.StatusCode, fmt.Errorf(
			"embedding HTTP %d: %s%s", response.StatusCode, strings.TrimSpace(string(detail)), suffix,
		)
	}
	var decoded struct {
		Data []struct {
			Index     int       `json:"index"`
			Embedding []float32 `json:"embedding"`
		} `json:"data"`
	}
	if err := json.Unmarshal(body, &decoded); err != nil {
		return nil, response.StatusCode, fmt.Errorf("decode embedding response: %w", err)
	}
	result := make([]Value, len(inputs))
	seen := make([]bool, len(inputs))
	for _, item := range decoded.Data {
		if item.Index < 0 || item.Index >= len(inputs) || seen[item.Index] {
			return nil, response.StatusCode, fmt.Errorf("invalid embedding response index %d", item.Index)
		}
		if len(item.Embedding) != p.cfg.Dimensions {
			return nil, response.StatusCode, fmt.Errorf(
				"embedding %d has %d dimensions, want %d", item.Index, len(item.Embedding), p.cfg.Dimensions,
			)
		}
		seen[item.Index] = true
		result[item.Index] = Value{ID: inputs[item.Index].ID, Vector: item.Embedding}
	}
	for i, ok := range seen {
		if !ok {
			return nil, response.StatusCode, fmt.Errorf("embedding response omitted input %d", i)
		}
	}
	return result, response.StatusCode, nil
}

func (p *httpProvider) Close() error { return nil }

func truncateChars(value string, limit int) string {
	runes := []rune(value)
	if len(runes) <= limit {
		return value
	}
	return string(runes[:limit])
}

func providerInputLimit(cfg config.Embedding) int {
	if cfg.MaxInputChars > 0 {
		return cfg.MaxInputChars
	}
	return defaultMaxProviderInputChars
}

type commandProvider struct {
	cfg       config.Embedding
	cmd       *exec.Cmd
	stdin     io.WriteCloser
	encoder   *json.Encoder
	responses chan commandResponse
	errors    chan error
	waitCh    chan error
	requests  int64
	stopOnce  sync.Once
}

type commandResponse struct {
	Type      string  `json:"type"`
	Schema    string  `json:"schema,omitempty"`
	RequestID string  `json:"requestId,omitempty"`
	Values    []Value `json:"values,omitempty"`
}

func startCommand(ctx context.Context, root string, cfg config.Embedding) (*commandProvider, error) {
	cmd := exec.CommandContext(ctx, cfg.Command[0], cfg.Command[1:]...)
	cmd.Dir = root
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	stderr := &boundedBuffer{limit: maxProviderStderr}
	cmd.Stderr = stderr
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	provider := &commandProvider{
		cfg:       cfg,
		cmd:       cmd,
		stdin:     stdin,
		encoder:   json.NewEncoder(stdin),
		responses: make(chan commandResponse),
		errors:    make(chan error, 1),
		waitCh:    make(chan error, 1),
	}
	go func() { provider.waitCh <- cmd.Wait() }()
	go provider.decode(stdout)
	if err := provider.encoder.Encode(map[string]any{
		"type": "hello", "schema": contracts.EmbeddingSchema,
		"model": cfg.Model, "dimensions": cfg.Dimensions,
	}); err != nil {
		provider.abort()
		return nil, err
	}
	ready, err := provider.await(ctx, 2*time.Second)
	if err != nil {
		provider.abort()
		return nil, fmt.Errorf("embedding command handshake: %w: %s", err, strings.TrimSpace(stderr.String()))
	}
	if ready.Type != "ready" || ready.Schema != contracts.EmbeddingSchema {
		provider.abort()
		return nil, errors.New("embedding command returned invalid ready message")
	}
	return provider, nil
}

func (p *commandProvider) Embed(ctx context.Context, inputs []Input) ([]Value, error) {
	p.requests++
	requestID := fmt.Sprintf("%d", p.requests)
	bounded := make([]Input, len(inputs))
	for index, input := range inputs {
		bounded[index] = Input{ID: input.ID, Text: truncateChars(input.Text, providerInputLimit(p.cfg))}
	}
	if err := p.encoder.Encode(map[string]any{
		"type": "embed", "requestId": requestID, "model": p.cfg.Model, "inputs": bounded,
	}); err != nil {
		return nil, err
	}
	result, err := p.await(ctx, time.Duration(p.cfg.TimeoutMS)*time.Millisecond)
	if err != nil {
		p.abort()
		return nil, err
	}
	if result.Type != "result" || result.RequestID != requestID {
		return nil, fmt.Errorf("expected embedding result %s", requestID)
	}
	if len(result.Values) != len(inputs) {
		return nil, fmt.Errorf("embedding command returned %d values, want %d", len(result.Values), len(inputs))
	}
	byID := make(map[string][]float32, len(result.Values))
	for _, value := range result.Values {
		if len(value.Vector) != p.cfg.Dimensions {
			return nil, fmt.Errorf("embedding %s has %d dimensions, want %d", value.ID, len(value.Vector), p.cfg.Dimensions)
		}
		if _, exists := byID[value.ID]; exists {
			return nil, fmt.Errorf("duplicate embedding id %q", value.ID)
		}
		byID[value.ID] = value.Vector
	}
	ordered := make([]Value, len(inputs))
	for i, input := range inputs {
		vector, ok := byID[input.ID]
		if !ok {
			return nil, fmt.Errorf("embedding command omitted id %q", input.ID)
		}
		ordered[i] = Value{ID: input.ID, Vector: vector}
	}
	return ordered, nil
}

func (p *commandProvider) Close() error {
	if err := p.encoder.Encode(map[string]string{"type": "end"}); err != nil {
		p.abort()
		return err
	}
	summary, err := p.await(context.Background(), 2*time.Second)
	if err != nil || summary.Type != "summary" {
		p.abort()
		if err != nil {
			return err
		}
		return errors.New("embedding command omitted summary")
	}
	_ = p.stdin.Close()
	select {
	case err := <-p.waitCh:
		return err
	case <-time.After(2 * time.Second):
		p.abort()
		return errors.New("embedding command did not exit")
	}
}

func (p *commandProvider) decode(reader io.Reader) {
	scanner := bufio.NewScanner(reader)
	scanner.Buffer(make([]byte, 64*1024), maxProviderResponse)
	for scanner.Scan() {
		var value commandResponse
		if err := json.Unmarshal(scanner.Bytes(), &value); err != nil {
			p.errors <- err
			close(p.responses)
			return
		}
		p.responses <- value
	}
	p.errors <- scanner.Err()
	close(p.responses)
}

func (p *commandProvider) await(ctx context.Context, timeout time.Duration) (commandResponse, error) {
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case value, ok := <-p.responses:
		if ok {
			return value, nil
		}
		select {
		case err := <-p.errors:
			if err != nil {
				return commandResponse{}, err
			}
		default:
		}
		return commandResponse{}, errors.New("embedding command output closed")
	case <-ctx.Done():
		return commandResponse{}, ctx.Err()
	case <-timer.C:
		return commandResponse{}, errors.New("embedding command response timed out")
	}
}

func (p *commandProvider) abort() {
	p.stopOnce.Do(func() {
		_ = p.stdin.Close()
		if p.cmd.Process != nil {
			_ = p.cmd.Process.Kill()
		}
	})
}

type boundedBuffer struct {
	mu    sync.Mutex
	value bytes.Buffer
	limit int
}

func (b *boundedBuffer) Write(data []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	remaining := b.limit - b.value.Len()
	if remaining > 0 {
		if len(data) < remaining {
			remaining = len(data)
		}
		_, _ = b.value.Write(data[:remaining])
	}
	return len(data), nil
}

func (b *boundedBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.value.String()
}
