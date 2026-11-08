package config

import (
	"os"
	"path/filepath"
	"strings"
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
			"dimensions":384,
			"queryPrefix":"Instruct: code search\nQuery: ",
			"documentPrefix":"Document: "
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
		cfg.Embedding.QueryPrefix != "Instruct: code search\nQuery: " ||
		cfg.Embedding.DocumentPrefix != "Document: " ||
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

func TestRepositoryPluginsReplaceOrDisableUserPlugins(t *testing.T) {
	configHome := t.TempDir()
	t.Setenv("YGG_CONFIG", "")
	t.Setenv("XDG_CONFIG_HOME", configHome)
	directory := filepath.Join(configHome, "ygg")
	if err := os.MkdirAll(directory, 0o755); err != nil {
		t.Fatal(err)
	}
	userPath := filepath.Join(directory, "config.json")
	userData := []byte(`{
		"schema":"ygg.config/v1",
		"plugins":[{"id":"u","version":"1","command":["/opt/u"],"includeGlobs":["**/*.py"]}]
	}`)
	if err := os.WriteFile(userPath, userData, 0o600); err != nil {
		t.Fatal(err)
	}
	for _, test := range []struct {
		name, repo string
		want       []string
		source     string
	}{
		{name: "no repository config", want: []string{"u"}, source: userPath},
		{name: "repository without plugins", repo: `{"schema":"ygg.config/v1","ignoreGlobs":["x"]}`,
			want: []string{"u"}, source: userPath},
		{name: "repository replaces", repo: `{"schema":"ygg.config/v1",
			"plugins":[{"id":"r","version":"1","command":["r"],"includeGlobs":["**/*.go"]}]}`,
			want: []string{"r"}, source: "repo"},
		{name: "repository disables", repo: `{"schema":"ygg.config/v1","plugins":[]}`},
	} {
		t.Run(test.name, func(t *testing.T) {
			root := t.TempDir()
			repoPath := filepath.Join(root, ".ygg", "config.json")
			if test.repo != "" {
				if err := os.Mkdir(filepath.Join(root, ".ygg"), 0o755); err != nil {
					t.Fatal(err)
				}
				if err := os.WriteFile(repoPath, []byte(test.repo), 0o644); err != nil {
					t.Fatal(err)
				}
			}
			cfg, err := Load(root)
			if err != nil {
				t.Fatal(err)
			}
			var ids []string
			for _, plugin := range cfg.Plugins {
				ids = append(ids, plugin.ID)
			}
			source := test.source
			if source == "repo" {
				source = repoPath
			}
			if strings.Join(ids, ",") != strings.Join(test.want, ",") || cfg.PluginSource != source {
				t.Fatalf("plugins=%v source=%q", ids, cfg.PluginSource)
			}
		})
	}
}

func TestUserPluginsAreValidated(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.json")
	t.Setenv("YGG_CONFIG", path)
	data := []byte(`{"schema":"ygg.config/v1","plugins":[{"id":"u","version":"1","command":[]}]}`)
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := Load(t.TempDir()); err == nil {
		t.Fatal("expected invalid user plugin to fail")
	}
}

func isolateUserConfig(t *testing.T) {
	t.Helper()
	t.Setenv("YGG_CONFIG", "")
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
}

func TestLoadUsesUserRerankWithDefaultsAndRepositoryOverride(t *testing.T) {
	configHome := t.TempDir()
	t.Setenv("YGG_CONFIG", "")
	t.Setenv("XDG_CONFIG_HOME", configHome)
	directory := filepath.Join(configHome, "ygg")
	if err := os.MkdirAll(directory, 0o755); err != nil {
		t.Fatal(err)
	}
	userData := []byte(`{
		"schema":"ygg.config/v1",
		"rerank":{"kind":"command","command":["/bin/worker"],"model":"fixture"}
	}`)
	if err := os.WriteFile(filepath.Join(directory, "config.json"), userData, 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err := Load(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Rerank == nil || cfg.Rerank.Depth != 20 || cfg.Rerank.MaxInputChars != 1500 ||
		cfg.Rerank.TimeoutMS != 60_000 || cfg.Rerank.Model != "fixture" {
		t.Fatalf("rerank=%#v", cfg.Rerank)
	}
	root := t.TempDir()
	if err := os.Mkdir(filepath.Join(root, ".ygg"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(
		filepath.Join(root, ".ygg", "config.json"),
		[]byte(`{"schema":"ygg.config/v1","rerank":null}`), 0o644,
	); err != nil {
		t.Fatal(err)
	}
	cfg, err = Load(root)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Rerank != nil {
		t.Fatalf("repository did not disable rerank: %#v", cfg.Rerank)
	}
}

func TestValidateRejectsInvalidRerank(t *testing.T) {
	for _, rerank := range []Rerank{
		{Kind: "grpc", Command: []string{"w"}},
		{Kind: "http", Command: []string{"w"}},
		{Kind: "command"},
		{Kind: "command", Command: []string{"w"}, Depth: 101},
		{Kind: "command", Command: []string{"w"}, Depth: -1},
		{Kind: "command", Command: []string{"w"}, MaxInputChars: 100_001},
	} {
		cfg := Default()
		cfg.Rerank = &rerank
		if err := cfg.Validate(); err == nil {
			t.Fatalf("accepted %#v", rerank)
		}
	}
}
