// Package rerank owns bounded command and HTTP reranking providers.
package rerank

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os/exec"
	"strings"
	"sync"
	"time"

	"github.com/coadan/yggdrasil/internal/config"
	"github.com/coadan/yggdrasil/internal/contracts"
	rerankcontract "github.com/coadan/yggdrasil/rerank"
)

const maxProviderResponse = 4 * 1024 * 1024
const maxProviderStderr = 1024 * 1024
const maxProviderError = 8 * 1024

type Input = rerankcontract.Input
type Score = rerankcontract.Score

func New(ctx context.Context, root string, cfg config.Rerank) (rerankcontract.Provider, error) {
	switch cfg.Kind {
	case "command":
		if len(cfg.Command) == 0 || cfg.Command[0] == "" {
			return nil, errors.New("rerank command is required")
		}
		return startCommand(ctx, root, cfg)
	case "http":
		if cfg.Endpoint == "" {
			return nil, errors.New("rerank endpoint is required")
		}
		return &httpProvider{
			cfg:    cfg,
			client: &http.Client{Timeout: time.Duration(cfg.TimeoutMS) * time.Millisecond},
		}, nil
	default:
		return nil, fmt.Errorf("unsupported rerank kind %q", cfg.Kind)
	}
}

// httpProvider speaks the Jina/Cohere-style rerank request that llama.cpp's
// server exposes at /v1/rerank.
type httpProvider struct {
	cfg    config.Rerank
	client *http.Client
}

func (p *httpProvider) Rerank(ctx context.Context, query string, inputs []Input) ([]Score, error) {
	documents := make([]string, len(inputs))
	for index, input := range inputs {
		documents[index] = input.Text
	}
	payload, err := json.Marshal(map[string]any{
		"model": p.cfg.Model, "query": query, "documents": documents, "top_n": len(documents),
	})
	if err != nil {
		return nil, err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, p.cfg.Endpoint, bytes.NewReader(payload))
	if err != nil {
		return nil, err
	}
	request.Header.Set("Content-Type", "application/json")
	response, err := p.client.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(io.LimitReader(response.Body, maxProviderError))
		return nil, fmt.Errorf("rerank endpoint returned %s: %s", response.Status, strings.TrimSpace(string(body)))
	}
	var decoded struct {
		Results []struct {
			Index          *int     `json:"index"`
			RelevanceScore *float64 `json:"relevance_score"`
		} `json:"results"`
	}
	if err := json.NewDecoder(io.LimitReader(response.Body, maxProviderResponse)).Decode(&decoded); err != nil {
		return nil, fmt.Errorf("decode rerank response: %w", err)
	}
	scores := make([]Score, 0, len(decoded.Results))
	for _, result := range decoded.Results {
		if result.Index == nil || result.RelevanceScore == nil {
			return nil, errors.New("rerank response result lacks index or relevance_score")
		}
		if *result.Index < 0 || *result.Index >= len(inputs) {
			return nil, fmt.Errorf("rerank response index %d is out of range", *result.Index)
		}
		scores = append(scores, Score{ID: inputs[*result.Index].ID, Score: *result.RelevanceScore})
	}
	return scores, nil
}

func (p *httpProvider) Close() error { return nil }

type commandProvider struct {
	cfg       config.Rerank
	cmd       *exec.Cmd
	stdin     io.WriteCloser
	encoder   *json.Encoder
	stderr    *boundedBuffer
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
	Scores    []Score `json:"scores,omitempty"`
}

func startCommand(ctx context.Context, root string, cfg config.Rerank) (*commandProvider, error) {
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
		stderr:    stderr,
		responses: make(chan commandResponse),
		errors:    make(chan error, 1),
		waitCh:    make(chan error, 1),
	}
	go func() { provider.waitCh <- cmd.Wait() }()
	go provider.decode(stdout)
	if err := provider.encoder.Encode(map[string]any{
		"type": "hello", "schema": contracts.RerankSchema, "model": cfg.Model,
	}); err != nil {
		provider.abort()
		return nil, err
	}
	ready, err := provider.await(ctx, 2*time.Second)
	if err != nil {
		provider.abort()
		return nil, fmt.Errorf("rerank command handshake: %w: %s", err, strings.TrimSpace(stderr.String()))
	}
	if ready.Type != "ready" || ready.Schema != contracts.RerankSchema {
		provider.abort()
		return nil, errors.New("rerank command returned invalid ready message")
	}
	return provider, nil
}

// Rerank sends one bounded request. Scores are returned as reported; the
// caller validates ids and values.
func (p *commandProvider) Rerank(ctx context.Context, query string, inputs []Input) ([]Score, error) {
	p.requests++
	requestID := fmt.Sprintf("%d", p.requests)
	if err := p.encoder.Encode(map[string]any{
		"type": "rerank", "requestId": requestID, "model": p.cfg.Model,
		"query": query, "inputs": inputs,
	}); err != nil {
		p.abort()
		return nil, err
	}
	result, err := p.await(ctx, time.Duration(p.cfg.TimeoutMS)*time.Millisecond)
	if err != nil {
		p.abort()
		if stderr := strings.TrimSpace(p.stderr.String()); stderr != "" {
			return nil, fmt.Errorf("%w: %s", err, lastLine(stderr))
		}
		return nil, err
	}
	if result.Type != "result" || result.RequestID != requestID {
		p.abort()
		return nil, fmt.Errorf("expected rerank result %s", requestID)
	}
	return result.Scores, nil
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
		return errors.New("rerank command omitted summary")
	}
	_ = p.stdin.Close()
	select {
	case err := <-p.waitCh:
		return err
	case <-time.After(2 * time.Second):
		p.abort()
		return errors.New("rerank command did not exit")
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
		return commandResponse{}, errors.New("rerank command output closed")
	case <-ctx.Done():
		return commandResponse{}, ctx.Err()
	case <-timer.C:
		return commandResponse{}, errors.New("rerank command response timed out")
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

func lastLine(value string) string {
	if index := strings.LastIndexByte(value, '\n'); index >= 0 {
		return value[index+1:]
	}
	return value
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
