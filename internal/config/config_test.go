package config

import (
	"os"
	"path/filepath"
	"testing"
)

func TestLoadDefaultsWhenConfigIsAbsent(t *testing.T) {
	cfg, err := Load(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Schema != "ygg.config/v1" || cfg.MaxFileBytes != DefaultMaxFileBytes {
		t.Fatalf("unexpected defaults: %#v", cfg)
	}
}

func TestLoadValidatesPlugins(t *testing.T) {
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
