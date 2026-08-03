// Package plugin runs bounded, repository-configured extractor processes.
package plugin

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os/exec"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/coadan/yggdrasil/internal/config"
	"github.com/coadan/yggdrasil/internal/contracts"
	"github.com/coadan/yggdrasil/internal/discovery"
)

const (
	handshakeTimeout = 2 * time.Second
	shutdownTimeout  = 2 * time.Second
	maxResponseBytes = 2 * 1024 * 1024
	maxRecordText    = 64 * 1024
	maxMetadataBytes = 16 * 1024
	maxRecords       = 513
	maxStderrBytes   = 8 * 1024
)

type Diagnostic struct {
	Path    string `json:"path,omitempty"`
	Plugin  string `json:"plugin"`
	Stage   string `json:"stage"`
	Message string `json:"message"`
}

type CheckResult struct {
	Plugin      string                   `json:"plugin"`
	Ready       bool                     `json:"ready"`
	Records     []contracts.SearchRecord `json:"records,omitempty"`
	Diagnostics []Diagnostic             `json:"diagnostics,omitempty"`
}

type Manager struct {
	ctx      context.Context
	root     string
	configs  []config.Plugin
	sessions map[string]*Session
	disabled map[string]bool
}

func NewManager(ctx context.Context, root string, plugins []config.Plugin) *Manager {
	return &Manager{
		ctx:      ctx,
		root:     root,
		configs:  plugins,
		sessions: map[string]*Session{},
		disabled: map[string]bool{},
	}
}

func (m *Manager) Extract(file discovery.File) ([]contracts.SearchRecord, []Diagnostic) {
	var records []contracts.SearchRecord
	var diagnostics []Diagnostic
	for _, pluginConfig := range m.configs {
		if m.disabled[pluginConfig.ID] || !applies(pluginConfig, file.Path) {
			continue
		}
		session := m.sessions[pluginConfig.ID]
		if session == nil {
			var err error
			session, err = Start(m.ctx, m.root, pluginConfig)
			if err != nil {
				m.disabled[pluginConfig.ID] = true
				diagnostics = append(diagnostics, diagnostic(file.Path, pluginConfig.ID, "start", err))
				continue
			}
			m.sessions[pluginConfig.ID] = session
		}
		extracted, pluginDiagnostics, err := session.Extract(m.ctx, file)
		if err != nil {
			m.disabled[pluginConfig.ID] = true
			session.abort()
			delete(m.sessions, pluginConfig.ID)
			diagnostics = append(diagnostics, diagnostic(file.Path, pluginConfig.ID, "extract", err))
			continue
		}
		records = append(records, extracted...)
		diagnostics = append(diagnostics, pluginDiagnostics...)
	}
	return records, diagnostics
}

func (m *Manager) Close() []Diagnostic {
	var diagnostics []Diagnostic
	for id, session := range m.sessions {
		if err := session.Close(); err != nil {
			diagnostics = append(diagnostics, diagnostic("", id, "shutdown", err))
		}
	}
	m.sessions = map[string]*Session{}
	return diagnostics
}

func Check(ctx context.Context, root string, pluginConfig config.Plugin, file *discovery.File) (CheckResult, error) {
	session, err := Start(ctx, root, pluginConfig)
	if err != nil {
		return CheckResult{}, err
	}
	result := CheckResult{Plugin: pluginConfig.ID, Ready: true}
	if file != nil {
		if !applies(pluginConfig, file.Path) {
			session.abort()
			return CheckResult{}, fmt.Errorf("plugin %q does not match %s", pluginConfig.ID, file.Path)
		}
		result.Records, result.Diagnostics, err = session.Extract(ctx, *file)
		if err != nil {
			session.abort()
			return CheckResult{}, err
		}
	}
	if err := session.Close(); err != nil {
		return CheckResult{}, err
	}
	return result, nil
}

func applies(pluginConfig config.Plugin, path string) bool {
	for _, pattern := range pluginConfig.IncludeGlobs {
		if discovery.Match(pattern, path) {
			return true
		}
	}
	return false
}

func diagnostic(path, pluginID, stage string, err error) Diagnostic {
	return Diagnostic{Path: path, Plugin: pluginID, Stage: stage, Message: err.Error()}
}

type Session struct {
	config    config.Plugin
	cmd       *exec.Cmd
	stdin     io.WriteCloser
	encoder   *json.Encoder
	responses chan response
	decodeErr chan error
	stderr    *limitedBuffer
	requests  int64
	stopOnce  sync.Once
	waitCh    chan error
}

type response struct {
	Type        string          `json:"type"`
	Schema      string          `json:"schema,omitempty"`
	RequestID   string          `json:"requestId,omitempty"`
	Records     json.RawMessage `json:"records,omitempty"`
	Diagnostics []struct {
		Message string `json:"message"`
	} `json:"diagnostics,omitempty"`
}

func Start(ctx context.Context, root string, pluginConfig config.Plugin) (*Session, error) {
	cmd := exec.CommandContext(ctx, pluginConfig.Command[0], pluginConfig.Command[1:]...)
	cmd.Dir = root
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	stderr := &limitedBuffer{limit: maxStderrBytes}
	cmd.Stderr = stderr
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	session := &Session{
		config:    pluginConfig,
		cmd:       cmd,
		stdin:     stdin,
		encoder:   json.NewEncoder(stdin),
		responses: make(chan response),
		decodeErr: make(chan error, 1),
		stderr:    stderr,
		waitCh:    make(chan error, 1),
	}
	go func() { session.waitCh <- cmd.Wait() }()
	go session.decode(stdout)
	hello := map[string]any{
		"type":       "hello",
		"schema":     contracts.ExtractorSchema,
		"plugin":     map[string]string{"id": pluginConfig.ID, "version": pluginConfig.Version},
		"repository": map[string]string{"root": root},
	}
	if err := session.encoder.Encode(hello); err != nil {
		session.abort()
		return nil, err
	}
	ready, err := session.await(ctx, handshakeTimeout)
	if err != nil {
		session.abort()
		return nil, fmt.Errorf("plugin %q handshake: %w%s", pluginConfig.ID, err, session.stderrSuffix())
	}
	if ready.Type != "ready" || ready.Schema != contracts.ExtractorSchema {
		session.abort()
		return nil, fmt.Errorf("plugin %q returned invalid ready message", pluginConfig.ID)
	}
	return session, nil
}

func (s *Session) Extract(ctx context.Context, file discovery.File) ([]contracts.SearchRecord, []Diagnostic, error) {
	s.requests++
	requestID := fmt.Sprintf("%d", s.requests)
	message := map[string]any{
		"type":      "file",
		"requestId": requestID,
		"file": map[string]any{
			"path":        file.Path,
			"kind":        file.Kind,
			"contentHash": file.ContentHash,
			"content":     file.Content,
		},
	}
	if err := s.encoder.Encode(message); err != nil {
		return nil, nil, err
	}
	value, err := s.await(ctx, time.Duration(s.config.TimeoutMS)*time.Millisecond)
	if err != nil {
		return nil, nil, fmt.Errorf("%w%s", err, s.stderrSuffix())
	}
	if value.Type != "result" || value.RequestID != requestID {
		return nil, nil, fmt.Errorf("expected result %s, got %q %q", requestID, value.Type, value.RequestID)
	}
	var rawRecords []contracts.SearchRecord
	if len(value.Records) > 0 {
		if err := json.Unmarshal(value.Records, &rawRecords); err != nil {
			return nil, nil, fmt.Errorf("plugin %q records: %w", s.config.ID, err)
		}
	}
	records, err := validateRecords(s.config, file, rawRecords)
	if err != nil {
		return nil, nil, err
	}
	diagnostics := make([]Diagnostic, 0, len(value.Diagnostics))
	for _, item := range value.Diagnostics {
		if strings.TrimSpace(item.Message) != "" {
			diagnostics = append(diagnostics, Diagnostic{
				Path: file.Path, Plugin: s.config.ID, Stage: "plugin", Message: item.Message,
			})
		}
	}
	return records, diagnostics, nil
}

func validateRecords(pluginConfig config.Plugin, file discovery.File, records []contracts.SearchRecord) ([]contracts.SearchRecord, error) {
	if len(records) > maxRecords {
		return nil, fmt.Errorf("plugin %q returned %d records; maximum is %d", pluginConfig.ID, len(records), maxRecords)
	}
	lineCount := strings.Count(file.Content, "\n") + 1
	ids := map[string]bool{}
	for i := range records {
		record := &records[i]
		if record.ID != "" {
			if ids[record.ID] {
				return nil, fmt.Errorf("plugin %q returned duplicate record id %q", pluginConfig.ID, record.ID)
			}
			ids[record.ID] = true
		}
		if record.Source != "" {
			return nil, fmt.Errorf("plugin %q attempted to set protected source", pluginConfig.ID)
		}
		if record.Path != "" && record.Path != file.Path {
			return nil, fmt.Errorf("plugin %q record references another file %q", pluginConfig.ID, record.Path)
		}
		record.Path = file.Path
		record.Source = "plugin:" + pluginConfig.ID
		if record.Kind == "" || strings.TrimSpace(record.Text) == "" {
			return nil, fmt.Errorf("plugin %q record %d requires kind and text", pluginConfig.ID, i)
		}
		if record.StartLine < 1 || record.EndLine < record.StartLine || record.EndLine > lineCount {
			return nil, fmt.Errorf("plugin %q record %d has invalid line range", pluginConfig.ID, i)
		}
		if len(record.Text) > maxRecordText {
			return nil, fmt.Errorf("plugin %q record %d text exceeds %d bytes", pluginConfig.ID, i, maxRecordText)
		}
		if !utf8.ValidString(record.Text) {
			return nil, fmt.Errorf("plugin %q record %d text is not UTF-8", pluginConfig.ID, i)
		}
		metadata, err := json.Marshal(record.Metadata)
		if err != nil || len(metadata) > maxMetadataBytes {
			return nil, fmt.Errorf("plugin %q record %d metadata is invalid or too large", pluginConfig.ID, i)
		}
	}
	return records, nil
}

func (s *Session) Close() error {
	if err := s.encoder.Encode(map[string]string{"type": "end"}); err != nil {
		s.abort()
		return err
	}
	value, err := s.await(context.Background(), shutdownTimeout)
	if err != nil {
		s.abort()
		return fmt.Errorf("plugin %q summary: %w%s", s.config.ID, err, s.stderrSuffix())
	}
	if value.Type != "summary" {
		s.abort()
		return fmt.Errorf("plugin %q expected summary, got %q", s.config.ID, value.Type)
	}
	_ = s.stdin.Close()
	return s.wait(shutdownTimeout)
}

func (s *Session) decode(reader io.Reader) {
	scanner := bufio.NewScanner(reader)
	scanner.Buffer(make([]byte, 64*1024), maxResponseBytes)
	for scanner.Scan() {
		var value response
		if err := json.Unmarshal(scanner.Bytes(), &value); err != nil {
			s.decodeErr <- err
			close(s.responses)
			return
		}
		s.responses <- value
	}
	s.decodeErr <- scanner.Err()
	close(s.responses)
}

func (s *Session) await(ctx context.Context, timeout time.Duration) (response, error) {
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case value, ok := <-s.responses:
		if !ok {
			select {
			case err := <-s.decodeErr:
				if err != nil {
					return response{}, err
				}
			default:
			}
			return response{}, errors.New("plugin output closed")
		}
		return value, nil
	case <-ctx.Done():
		return response{}, ctx.Err()
	case <-timer.C:
		return response{}, errors.New("plugin response timed out")
	}
}

func (s *Session) wait(timeout time.Duration) error {
	select {
	case err := <-s.waitCh:
		return err
	case <-time.After(timeout):
		s.abort()
		return errors.New("plugin did not exit")
	}
}

func (s *Session) abort() {
	s.stopOnce.Do(func() {
		_ = s.stdin.Close()
		if s.cmd.Process != nil {
			_ = s.cmd.Process.Kill()
		}
	})
}

func (s *Session) stderrSuffix() string {
	value := strings.TrimSpace(s.stderr.String())
	if value == "" {
		return ""
	}
	return ": " + value
}

type limitedBuffer struct {
	mu    sync.Mutex
	data  []byte
	limit int
}

func (b *limitedBuffer) Write(value []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	remaining := b.limit - len(b.data)
	if remaining > 0 {
		b.data = append(b.data, value[:min(len(value), remaining)]...)
	}
	return len(value), nil
}

func (b *limitedBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return string(b.data)
}
