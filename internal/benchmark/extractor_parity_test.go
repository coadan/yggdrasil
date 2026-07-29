package benchmark

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"testing"
)

func TestExtractorParityInventoryCoversLegacyKinds(t *testing.T) {
	data, err := os.ReadFile(filepath.Join("..", "..", "benchmarks", "extractor-parity.json"))
	if err != nil {
		t.Fatal(err)
	}
	var inventory struct {
		Schema     string `json:"schema"`
		Structured []struct {
			Kind     string   `json:"kind"`
			Coverage string   `json:"coverage"`
			Owner    string   `json:"owner"`
			Facts    []string `json:"facts"`
		} `json:"structured"`
		BaselineOnly []string `json:"baseline-only"`
		Retired      []string `json:"retired"`
	}
	if err := json.Unmarshal(data, &inventory); err != nil {
		t.Fatal(err)
	}
	if inventory.Schema != "ygg.extractor-parity/v1" {
		t.Fatalf("schema=%q", inventory.Schema)
	}
	seen := map[string]bool{}
	var actual []string
	add := func(kind string) {
		if seen[kind] {
			t.Errorf("duplicate legacy kind %q", kind)
		}
		seen[kind] = true
		actual = append(actual, kind)
	}
	for _, value := range inventory.Structured {
		if value.Coverage != "partial" && value.Coverage != "complete" {
			t.Errorf("%s coverage=%q", value.Kind, value.Coverage)
		}
		if value.Owner == "" || len(value.Facts) == 0 {
			t.Errorf("%s lacks structured evidence", value.Kind)
		}
		add(value.Kind)
	}
	for _, kind := range inventory.BaselineOnly {
		add(kind)
	}
	for _, kind := range inventory.Retired {
		add(kind)
	}
	expected := []string{
		"code", "go", "java", "groovy", "kotlin", "swift", "objective-c", "dotnet",
		"ruby", "cpp", "dart", "scala", "elixir", "erlang", "lua", "r", "julia",
		"ocaml", "perl", "haskell", "odin", "zig", "apple-config", "astro", "prisma",
		"dbt", "notebook", "data-science", "devcontainer", "kustomize",
		"pre-commit-config", "codeowners", "task-runner", "starlark",
		"tool-version-config", "storybook", "docs-config", "editor-config",
		"release-config", "web-framework", "workflow-orchestration", "governance",
		"sbom", "observability-config", "db-config", "db-migration", "codegen-config",
		"ops-config", "php", "vue", "svelte", "ci", "build", "test-config",
		"quality-config", "tool-config", "javascript", "typescript", "python", "rust",
		"style", "sql", "terraform", "openapi", "asyncapi", "json-schema", "avro",
		"graphql", "protobuf", "gettext", "html", "svg", "xml", "env", "text",
		"image-asset", "font-asset", "media-asset", "archive-asset",
		"compiled-artifact", "opaque-asset", "secret-material", "gettext-binary",
		"doc", "edn", "config", "unknown", "yaml", "manifest", "dependency-lock",
		"docker", "procfile", "compose", "helm", "shell",
	}
	sort.Strings(actual)
	sort.Strings(expected)
	if !reflect.DeepEqual(actual, expected) {
		t.Fatalf("inventory mismatch\nactual=%v\nexpected=%v", actual, expected)
	}
}
