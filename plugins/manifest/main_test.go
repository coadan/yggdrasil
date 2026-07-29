package main

import (
	"encoding/json"
	"testing"
)

func TestPackageJSONFacts(t *testing.T) {
	records, diagnostics := extractPackageJSON(`{
  "name": "@acme/panels",
  "version": "1.2.3",
  "packageManager": "pnpm@9.0.0",
  "dependencies": {"react": "^18.3.0", "@acme/shared": "workspace:*"},
  "devDependencies": {"typescript": "^5.5.0"},
  "scripts": {"build": "turbo build"},
  "workspaces": ["apps/*", "packages/*"]
}`)
	if len(diagnostics) != 0 {
		t.Fatal(diagnostics)
	}
	assertRecord(t, records, "npm-package", "@acme/panels", "")
	assertRecord(t, records, "npm-dependency", "react", "runtime")
	assertRecord(t, records, "npm-dependency", "typescript", "development")
	assertRecord(t, records, "workspace-member", "apps/*", "")
	assertRecord(t, records, "package-script", "build", "")
}

func TestGoModFacts(t *testing.T) {
	records := extractGoMod(`module example.com/app

go 1.23
toolchain go1.23.2
require (
  github.com/acme/lib v1.2.3
)
require golang.org/x/sync v0.7.0
replace github.com/acme/old => ./local/old
exclude github.com/acme/bad v0.1.0
`)
	assertRecord(t, records, "go-module", "example.com/app", "")
	assertRecord(t, records, "go-dependency", "github.com/acme/lib", "runtime")
	assertRecord(t, records, "go-dependency", "golang.org/x/sync", "runtime")
	assertRecord(t, records, "go-replace", "github.com/acme/old=>./local/old", "")
	assertRecord(t, records, "go-exclude", "github.com/acme/bad@v0.1.0", "")
}

func TestCargoFacts(t *testing.T) {
	records := extractCargo(`[package]
name = "demo"

[dependencies]
serde = "1"
tokio = { version = "1.38" }

[workspace]
members = ["crates/api", "crates/core"]

[features]
postgres = []
`)
	assertRecord(t, records, "cargo-package", "demo", "")
	assertRecord(t, records, "cargo-dependency", "serde", "runtime")
	assertRecord(t, records, "cargo-dependency", "tokio", "runtime")
	assertRecord(t, records, "workspace-member", "crates/api", "")
	assertRecord(t, records, "cargo-feature", "postgres", "")
}

func TestPyprojectFacts(t *testing.T) {
	records := extractPyproject(`[project]
name = "demo-py"
dependencies = ["requests>=2", "click"]

[project.optional-dependencies]
dev = ["pytest"]
`)
	assertRecord(t, records, "pypi-project", "demo-py", "")
	assertRecord(t, records, "pypi-dependency", "requests", "runtime")
	assertRecord(t, records, "pypi-dependency", "click", "runtime")
	assertRecord(t, records, "pypi-dependency", "pytest", "dev")
}

func assertRecord(t *testing.T, records []record, kind, title, scope string) {
	t.Helper()
	for _, value := range records {
		if value.Kind != kind || value.Title != title {
			continue
		}
		if scope != "" && value.Metadata["scope"] != scope {
			t.Fatalf("%s:%s metadata=%s", kind, title, mustJSON(value.Metadata))
		}
		if value.StartLine < 1 || value.EndLine != value.StartLine {
			t.Fatalf("%s:%s lines=%d-%d", kind, title, value.StartLine, value.EndLine)
		}
		return
	}
	t.Fatalf("missing %s:%s in %s", kind, title, mustJSON(records))
}

func mustJSON(value any) string {
	data, _ := json.Marshal(value)
	return string(data)
}
