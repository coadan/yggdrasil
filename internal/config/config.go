// Package config loads the optional repository-local Yggdrasil configuration.
package config

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"

	"github.com/coadan/yggdrasil/extractor"
	"github.com/coadan/yggdrasil/internal/contracts"
)

const (
	DefaultMaxFileBytes   = int64(4 * 1024 * 1024)
	DefaultTimeoutMS      = 10_000
	DefaultBatchSize      = 64
	CoreExtractionVersion = "v3"
)

type Plugin struct {
	ID           string   `json:"id"`
	Version      string   `json:"version"`
	Command      []string `json:"command"`
	IncludeGlobs []string `json:"includeGlobs"`
	TimeoutMS    int      `json:"timeoutMs,omitempty"`
}

type Embedding struct {
	Kind           string   `json:"kind"`
	Command        []string `json:"command,omitempty"`
	Endpoint       string   `json:"endpoint,omitempty"`
	Model          string   `json:"model"`
	Dimensions     int      `json:"dimensions"`
	QueryPrefix    string   `json:"queryPrefix,omitempty"`
	DocumentPrefix string   `json:"documentPrefix,omitempty"`
	APIKeyEnv      string   `json:"apiKeyEnv,omitempty"`
	TimeoutMS      int      `json:"timeoutMs,omitempty"`
	BatchSize      int      `json:"batchSize,omitempty"`
	MaxInputChars  int      `json:"maxInputChars,omitempty"`
	Fingerprint    string   `json:"-"`
}

// Rerank configures an optional command that rescores leading results of
// natural-language searches.
type Rerank struct {
	Kind          string   `json:"kind"`
	Command       []string `json:"command"`
	Model         string   `json:"model,omitempty"`
	Depth         int      `json:"depth,omitempty"`
	MaxInputChars int      `json:"maxInputChars,omitempty"`
	TimeoutMS     int      `json:"timeoutMs,omitempty"`
}

type Config struct {
	Schema       string     `json:"schema"`
	IgnoreGlobs  []string   `json:"ignoreGlobs,omitempty"`
	MaxFileBytes int64      `json:"maxFileBytes,omitempty"`
	Plugins      []Plugin   `json:"plugins,omitempty"`
	Embedding    *Embedding `json:"embedding,omitempty"`
	Rerank       *Rerank    `json:"rerank,omitempty"`

	UserConfigPath       string `json:"-"`
	UserConfigLoaded     bool   `json:"-"`
	RepositoryConfigPath string `json:"-"`
	EmbeddingSource      string `json:"-"`
	EmbeddingDisabled    bool   `json:"-"`
}

type userConfig struct {
	Schema    string     `json:"schema"`
	Embedding *Embedding `json:"embedding,omitempty"`
	Rerank    *Rerank    `json:"rerank,omitempty"`
}

func Default() Config {
	return Config{
		Schema:       contracts.ConfigSchema,
		MaxFileBytes: DefaultMaxFileBytes,
	}
}

func Load(root string) (Config, error) {
	cfg := Default()
	userPath, err := userConfigPath()
	if err != nil {
		return Config{}, err
	}
	cfg.UserConfigPath = userPath
	if user, found, err := loadUser(userPath); err != nil {
		return Config{}, err
	} else if found {
		cfg.UserConfigLoaded = true
		cfg.Embedding = user.Embedding
		cfg.Rerank = user.Rerank
		if user.Embedding != nil {
			cfg.EmbeddingSource = userPath
		}
	}
	path := filepath.Join(root, ".ygg", "config.json")
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return cfg, nil
	}
	if err != nil {
		return Config{}, fmt.Errorf("read config: %w", err)
	}
	cfg.RepositoryConfigPath = path
	if err := decodeOne(data, &cfg); err != nil {
		return Config{}, fmt.Errorf("decode %s: %w", path, err)
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(data, &fields); err != nil {
		return Config{}, fmt.Errorf("decode %s fields: %w", path, err)
	}
	if _, explicit := fields["embedding"]; explicit {
		cfg.EmbeddingSource = ""
		cfg.EmbeddingDisabled = cfg.Embedding == nil
		if cfg.Embedding != nil {
			cfg.EmbeddingSource = path
		}
	}
	if err := cfg.Validate(); err != nil {
		return Config{}, fmt.Errorf("%s: %w", path, err)
	}
	return cfg, nil
}

func userConfigPath() (string, error) {
	if path := os.Getenv("YGG_CONFIG"); path != "" {
		absolute, err := filepath.Abs(path)
		if err != nil {
			return "", fmt.Errorf("resolve YGG_CONFIG: %w", err)
		}
		return absolute, nil
	}
	base := os.Getenv("XDG_CONFIG_HOME")
	if base == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", fmt.Errorf("resolve user config home: %w", err)
		}
		base = filepath.Join(home, ".config")
	}
	return filepath.Join(base, "ygg", "config.json"), nil
}

func loadUser(path string) (userConfig, bool, error) {
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return userConfig{}, false, nil
	}
	if err != nil {
		return userConfig{}, false, fmt.Errorf("read user config %s: %w", path, err)
	}
	var user userConfig
	if err := decodeOne(data, &user); err != nil {
		return userConfig{}, false, fmt.Errorf("decode user config %s: %w", path, err)
	}
	cfg := Default()
	cfg.Schema = user.Schema
	cfg.Embedding = user.Embedding
	cfg.Rerank = user.Rerank
	if err := cfg.Validate(); err != nil {
		return userConfig{}, false, fmt.Errorf("%s: %w", path, err)
	}
	return userConfig{Schema: cfg.Schema, Embedding: cfg.Embedding, Rerank: cfg.Rerank}, true, nil
}

func decodeOne(data []byte, target any) error {
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		return err
	}
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		if err == nil {
			err = errors.New("multiple JSON values")
		}
		return err
	}
	return nil
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
		if e.MaxInputChars <= 0 {
			e.MaxInputChars = 6_000
		}
		if e.MaxInputChars > 100_000 {
			return errors.New("embedding maxInputChars cannot exceed 100000")
		}
	}
	if c.Rerank != nil {
		r := c.Rerank
		if r.Kind != "command" {
			return fmt.Errorf("unsupported rerank kind %q", r.Kind)
		}
		if len(r.Command) == 0 || r.Command[0] == "" {
			return errors.New("rerank command is required")
		}
		if r.Depth < 0 || r.Depth > 100 {
			return errors.New("rerank depth must be between 1 and 100")
		}
		if r.Depth == 0 {
			r.Depth = 20
		}
		if r.MaxInputChars < 0 || r.MaxInputChars > 100_000 {
			return errors.New("rerank maxInputChars must be between 1 and 100000")
		}
		if r.MaxInputChars == 0 {
			r.MaxInputChars = 1_500
		}
		if r.TimeoutMS <= 0 {
			r.TimeoutMS = 60_000
		}
	}
	return nil
}

func ExtractionFingerprint(c Config) string {
	descriptors := make([]extractor.Descriptor, len(c.Plugins))
	for index, plugin := range c.Plugins {
		descriptors[index] = extractor.CommandDescriptor(extractor.CommandSpec{
			ID: plugin.ID, Version: plugin.Version,
			Command: plugin.Command, IncludeGlobs: plugin.IncludeGlobs,
			TimeoutMS: plugin.TimeoutMS,
		})
	}
	return ExtractionFingerprintWithProviders(c, descriptors)
}

func ExtractionFingerprintWithProviders(c Config, descriptors []extractor.Descriptor) string {
	payload := struct {
		CoreVersion  string                 `json:"coreVersion"`
		MaxFileBytes int64                  `json:"maxFileBytes"`
		IgnoreGlobs  []string               `json:"ignoreGlobs"`
		Extractors   []extractor.Descriptor `json:"extractors"`
	}{
		CoreVersion:  CoreExtractionVersion,
		MaxFileBytes: c.MaxFileBytes,
		IgnoreGlobs:  c.IgnoreGlobs,
		Extractors:   descriptors,
	}
	data, _ := json.Marshal(payload)
	sum := sha256.Sum256(data)
	return "sha256:" + hex.EncodeToString(sum[:])
}
