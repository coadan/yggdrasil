// Package config loads the optional repository-local Yggdrasil configuration.
package config

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"

	"github.com/coadan/yggdrasil/internal/contracts"
)

const (
	DefaultMaxFileBytes = int64(4 * 1024 * 1024)
	DefaultTimeoutMS    = 10_000
	DefaultBatchSize    = 64
)

type Plugin struct {
	ID           string   `json:"id"`
	Version      string   `json:"version"`
	Command      []string `json:"command"`
	IncludeGlobs []string `json:"includeGlobs"`
	TimeoutMS    int      `json:"timeoutMs,omitempty"`
}

type Embedding struct {
	Kind       string   `json:"kind"`
	Command    []string `json:"command,omitempty"`
	Endpoint   string   `json:"endpoint,omitempty"`
	Model      string   `json:"model"`
	Dimensions int      `json:"dimensions"`
	APIKeyEnv  string   `json:"apiKeyEnv,omitempty"`
	TimeoutMS  int      `json:"timeoutMs,omitempty"`
	BatchSize  int      `json:"batchSize,omitempty"`
}

type Config struct {
	Schema       string     `json:"schema"`
	IgnoreGlobs  []string   `json:"ignoreGlobs,omitempty"`
	MaxFileBytes int64      `json:"maxFileBytes,omitempty"`
	Plugins      []Plugin   `json:"plugins,omitempty"`
	Embedding    *Embedding `json:"embedding,omitempty"`
}

func Default() Config {
	return Config{
		Schema:       contracts.ConfigSchema,
		MaxFileBytes: DefaultMaxFileBytes,
	}
}

func Load(root string) (Config, error) {
	path := filepath.Join(root, ".ygg", "config.json")
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return Default(), nil
	}
	if err != nil {
		return Config{}, fmt.Errorf("read config: %w", err)
	}
	cfg := Default()
	if err := json.Unmarshal(data, &cfg); err != nil {
		return Config{}, fmt.Errorf("decode %s: %w", path, err)
	}
	if err := cfg.Validate(); err != nil {
		return Config{}, fmt.Errorf("%s: %w", path, err)
	}
	return cfg, nil
}

func (c *Config) Validate() error {
	if c.Schema != contracts.ConfigSchema {
		return fmt.Errorf("unsupported schema %q", c.Schema)
	}
	if c.MaxFileBytes <= 0 {
		c.MaxFileBytes = DefaultMaxFileBytes
	}
	seen := map[string]bool{}
	for i := range c.Plugins {
		p := &c.Plugins[i]
		if p.ID == "" {
			return errors.New("plugin id is required")
		}
		if seen[p.ID] {
			return fmt.Errorf("duplicate plugin id %q", p.ID)
		}
		seen[p.ID] = true
		if p.Version == "" {
			return fmt.Errorf("plugin %q version is required", p.ID)
		}
		if len(p.Command) == 0 || p.Command[0] == "" {
			return fmt.Errorf("plugin %q command is required", p.ID)
		}
		if len(p.IncludeGlobs) == 0 {
			return fmt.Errorf("plugin %q includeGlobs is required", p.ID)
		}
		if p.TimeoutMS <= 0 {
			p.TimeoutMS = DefaultTimeoutMS
		}
	}
	if c.Embedding != nil {
		e := c.Embedding
		if e.Kind != "command" && e.Kind != "openai-compatible" {
			return fmt.Errorf("unsupported embedding kind %q", e.Kind)
		}
		if e.Model == "" || e.Dimensions <= 0 {
			return errors.New("embedding model and positive dimensions are required")
		}
		if e.Kind == "command" && (len(e.Command) == 0 || e.Command[0] == "") {
			return errors.New("embedding command is required")
		}
		if e.Kind == "openai-compatible" && e.Endpoint == "" {
			return errors.New("embedding endpoint is required")
		}
		if e.TimeoutMS <= 0 {
			e.TimeoutMS = 2_000
		}
		if e.BatchSize <= 0 {
			e.BatchSize = DefaultBatchSize
		}
	}
	return nil
}

func ExtractionFingerprint(c Config) string {
	payload := struct {
		MaxFileBytes int64    `json:"maxFileBytes"`
		IgnoreGlobs  []string `json:"ignoreGlobs"`
		Plugins      []Plugin `json:"plugins"`
	}{
		MaxFileBytes: c.MaxFileBytes,
		IgnoreGlobs:  c.IgnoreGlobs,
		Plugins:      c.Plugins,
	}
	data, _ := json.Marshal(payload)
	sum := sha256.Sum256(data)
	return "sha256:" + hex.EncodeToString(sum[:])
}
