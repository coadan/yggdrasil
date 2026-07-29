package config

import (
	"os"
	"path/filepath"
	"testing"
)

func TestLoadDefaultsWhenConfigIsAbsent(t *testing.T) {
	isolateUserConfig(t)
	cfg, err := Load(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Schema != "ygg.config/v1" || cfg.MaxFileBytes != DefaultMaxFileBytes {
		t.Fatalf("unexpected defaults: %#v", cfg)
	}
}

func TestLoadValidatesPlugins(t *testing.T) {
	isolateUserConfig(t)
	root := t.TempDir()
	if err := os.Mkdir(filepath.Join(root, ".ygg"), 0o755); err != nil {
		t.Fatal(err)
	}
	data := []byte(`{"schema":"ygg.config/v1","plugins":[{"id":"m","version":"1","command":["m"],"includeGlobs":["**/*.md"]}]}`)
	if err := os.WriteFile(filepath.Join(root, ".ygg", "config.json"), data, 0o644); err != nil {
		t.Fatal(err)
	}
	cfg, err := Load(root)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Plugins[0].TimeoutMS != DefaultTimeoutMS {
		t.Fatalf("timeout default not applied: %#v", cfg.Plugins[0])
	}
}

func TestLoadRejectsUnknownFields(t *testing.T) {
	isolateUserConfig(t)
	root := t.TempDir()
	if err := os.Mkdir(filepath.Join(root, ".ygg"), 0o755); err != nil {
		t.Fatal(err)
	}
	data := []byte(`{"schema":"ygg.config/v1","maxFilesBytes":42}`)
	if err := os.WriteFile(filepath.Join(root, ".ygg", "config.json"), data, 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := Load(root); err == nil {
		t.Fatal("expected unknown configuration field to fail")
	}
}

func TestLoadRejectsTrailingJSONValue(t *testing.T) {
	isolateUserConfig(t)
	root := t.TempDir()
	if err := os.Mkdir(filepath.Join(root, ".ygg"), 0o755); err != nil {
		t.Fatal(err)
	}
	data := []byte(`{"schema":"ygg.config/v1"} {"schema":"ygg.config/v1"}`)
	if err := os.WriteFile(filepath.Join(root, ".ygg", "config.json"), data, 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := Load(root); err == nil {
		t.Fatal("expected multiple configuration values to fail")
	}
}

func TestLoadUsesUserEmbeddingAcrossRepositories(t *testing.T) {
	configHome := t.TempDir()
	t.Setenv("YGG_CONFIG", "")
	t.Setenv("XDG_CONFIG_HOME", configHome)
	directory := filepath.Join(configHome, "ygg")
	if err := os.MkdirAll(directory, 0o755); err != nil {
		t.Fatal(err)
	}
	data := []byte(`{
		"schema":"ygg.config/v1",
		"embedding":{
			"kind":"openai-compatible",
			"endpoint":"http://127.0.0.1:11434/v1/embeddings",
			"model":"all-minilm:latest",
			"dimensions":384
		}
	}`)
	if err := os.WriteFile(filepath.Join(directory, "config.json"), data, 0o600); err != nil {
		t.Fatal(err)
	}
	root := t.TempDir()
	if err := os.Mkdir(filepath.Join(root, ".ygg"), 0o755); err != nil {
		t.Fatal(err)
	}
	repoData := []byte(`{
		"schema":"ygg.config/v1",
		"plugins":[{"id":"m","version":"1","command":["m"],"includeGlobs":["**/*.md"]}]
	}`)
	if err := os.WriteFile(filepath.Join(root, ".ygg", "config.json"), repoData, 0o644); err != nil {
		t.Fatal(err)
	}
	cfg, err := Load(root)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Embedding == nil || cfg.Embedding.Model != "all-minilm:latest" ||
		cfg.Embedding.BatchSize != DefaultBatchSize || len(cfg.Plugins) != 1 ||
		!cfg.UserConfigLoaded || cfg.EmbeddingSource != filepath.Join(directory, "config.json") ||
		cfg.RepositoryConfigPath != filepath.Join(root, ".ygg", "config.json") {
		t.Fatalf("config=%#v", cfg)
	}
}

func TestRepositoryCanDisableUserEmbedding(t *testing.T) {
	configHome := t.TempDir()
	t.Setenv("YGG_CONFIG", "")
	t.Setenv("XDG_CONFIG_HOME", configHome)
	directory := filepath.Join(configHome, "ygg")
	if err := os.MkdirAll(directory, 0o755); err != nil {
		t.Fatal(err)
	}
	userData := []byte(`{
		"schema":"ygg.config/v1",
		"embedding":{
			"kind":"openai-compatible","endpoint":"http://localhost",
			"model":"fixture","dimensions":2
		}
	}`)
	if err := os.WriteFile(filepath.Join(directory, "config.json"), userData, 0o600); err != nil {
		t.Fatal(err)
	}
	root := t.TempDir()
	if err := os.Mkdir(filepath.Join(root, ".ygg"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(
		filepath.Join(root, ".ygg", "config.json"),
		[]byte(`{"schema":"ygg.config/v1","embedding":null}`), 0o644,
	); err != nil {
		t.Fatal(err)
	}
	cfg, err := Load(root)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Embedding != nil || !cfg.EmbeddingDisabled ||
		cfg.RepositoryConfigPath != filepath.Join(root, ".ygg", "config.json") {
		t.Fatalf("config=%#v", cfg)
	}
}

func isolateUserConfig(t *testing.T) {
	t.Helper()
	t.Setenv("YGG_CONFIG", "")
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
}
