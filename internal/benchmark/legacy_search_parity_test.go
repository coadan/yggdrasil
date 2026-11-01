package benchmark

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"

	"github.com/coadan/yggdrasil/internal/config"
	"github.com/coadan/yggdrasil/internal/indexer"
	"github.com/coadan/yggdrasil/internal/project"
	"github.com/coadan/yggdrasil/internal/store"
	search "github.com/coadan/yggdrasil/query"
)

type parityCase struct {
	Kind string `json:"kind"`
	Path string `json:"path"`
}

func TestLegacyTextKindsRemainSearchableAndRetiredKindsStayOut(t *testing.T) {
	var fixture struct {
		Schema   string       `json:"schema"`
		Retained []parityCase `json:"retained"`
		Retired  []parityCase `json:"retired"`
	}
	readJSONFile(t, filepath.Join("..", "..", "benchmarks", "legacy-search-parity.json"), &fixture)
	if fixture.Schema != "ygg.legacy-search-parity/v1" {
		t.Fatalf("schema=%q", fixture.Schema)
	}
	assertParityDispositions(t, fixture.Retained, fixture.Retired)

	root := t.TempDir()
	t.Setenv("YGG_STORAGE_ROOT", t.TempDir())
	markers := make(map[string]string, len(fixture.Retained))
	for _, item := range fixture.Retained {
		marker := "legacyparity" + strings.ReplaceAll(item.Kind, "-", "")
		content := marker + "\n"
		if item.Kind == "env" {
			marker = "legacyparityenv"
			content = "LEGACYPARITYENV=ultraconfidentialvalue\n"
		}
		markers[item.Path] = marker
		writeFixture(t, root, item.Path, []byte(content))
	}
	for _, item := range fixture.Retired {
		content := []byte{0, 1, 2}
		if item.Kind == "secret-material" {
			content = []byte("retiredsecretvalue\n")
		}
		writeFixture(t, root, item.Path, content)
	}

	paths, err := project.Resolve(root)
	if err != nil {
		t.Fatal(err)
	}
	summary, err := indexer.Run(context.Background(), paths, config.Default(), indexer.Options{Full: true})
	if err != nil {
		t.Fatal(err)
	}
	if summary.Scanned != len(fixture.Retained)+len(fixture.Retired) ||
		summary.Indexed != len(fixture.Retained) ||
		summary.Skipped != len(fixture.Retired) {
		t.Fatalf("summary=%#v", summary)
	}
	value, err := store.Open(context.Background(), paths.Database, paths.Root, paths.ID)
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	counts, err := value.Counts(context.Background())
	if err != nil || counts.Files != len(fixture.Retained) {
		t.Fatalf("counts=%#v err=%v", counts, err)
	}

	for _, item := range fixture.Retained {
		result, err := search.Run(context.Background(), value, markers[item.Path], search.Options{
			Mode: "lexical", Limit: 1,
		})
		if err != nil {
			t.Fatalf("%s: %v", item.Kind, err)
		}
		if len(result.Records) != 1 ||
			result.Records[0].Path != item.Path ||
			result.Records[0].StartLine != 1 ||
			result.Records[0].Excerpt == "" {
			t.Fatalf("%s result=%#v", item.Kind, result)
		}
	}
	for _, query := range []string{"ultraconfidentialvalue", "retiredsecretvalue"} {
		result, err := search.Run(context.Background(), value, query, search.Options{
			Mode: "lexical", Limit: 10,
		})
		if err != nil {
			t.Fatal(err)
		}
		if len(result.Records) != 0 {
			t.Fatalf("%q leaked through search: %#v", query, result.Records)
		}
	}
}

func assertParityDispositions(t *testing.T, retained, retired []parityCase) {
	t.Helper()
	var inventory struct {
		Structured []struct {
			Kind string `json:"kind"`
		} `json:"structured"`
		BaselineOnly []string `json:"baseline-only"`
		Retired      []string `json:"retired"`
	}
	readJSONFile(t, filepath.Join("..", "..", "benchmarks", "extractor-parity.json"), &inventory)
	var expectedRetained []string
	for _, value := range inventory.Structured {
		expectedRetained = append(expectedRetained, value.Kind)
	}
	expectedRetained = append(expectedRetained, inventory.BaselineOnly...)
	var actualRetained []string
	for _, value := range retained {
		actualRetained = append(actualRetained, value.Kind)
	}
	var actualRetired []string
	for _, value := range retired {
		actualRetired = append(actualRetired, value.Kind)
	}
	sort.Strings(expectedRetained)
	sort.Strings(actualRetained)
	sort.Strings(inventory.Retired)
	sort.Strings(actualRetired)
	if strings.Join(expectedRetained, "\n") != strings.Join(actualRetained, "\n") {
		t.Fatalf("retained parity cases do not match inventory")
	}
	if strings.Join(inventory.Retired, "\n") != strings.Join(actualRetired, "\n") {
		t.Fatalf("retired parity cases do not match inventory")
	}
}

func readJSONFile(t *testing.T, name string, target any) {
	t.Helper()
	data, err := os.ReadFile(name)
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(data, target); err != nil {
		t.Fatal(err)
	}
}

func writeFixture(t *testing.T, root, relative string, content []byte) {
	t.Helper()
	name := filepath.Join(root, filepath.FromSlash(relative))
	if err := os.MkdirAll(filepath.Dir(name), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(name, content, 0o600); err != nil {
		t.Fatal(err)
	}
}
