package benchmark

import (
	"os"
	"path/filepath"
	"testing"
)

func TestScoreUsesFileRanks(t *testing.T) {
	recall, mrr, noise := score(
		[]string{"noise", "src/a.go", "more-noise", "src/b.go"},
		[]string{"src/a.go", "src/b.go"},
	)
	if recall != 1 || mrr != 0.5 || noise != 0.5 {
		t.Fatalf("recall=%v mrr=%v noise=%v", recall, mrr, noise)
	}
}

func TestLoadSuiteValidatesAndHashes(t *testing.T) {
	path := filepath.Join(t.TempDir(), "suite.json")
	data := []byte(`{
		"schema":"ygg.benchmark.suite/v1",
		"id":"fixture",
		"repos":[{"id":"repo","url":"https://example.test/repo.git"}],
		"cases":[{
			"id":"case","repositoryId":"repo",
			"revision":"0123456789012345678901234567890123456789",
			"fixRevision":"abcdefabcdefabcdefabcdefabcdefabcdefabcd",
			"sourceUrl":"https://example.test/repo/pull/1",
			"query":"find owner","expectedPaths":["owner.go"],
			"sourceKinds":["go"],"problemClasses":["problem-implementation"],
			"architectureClasses":["architecture-data-ownership"]
		}]
	}`)
	if err := os.WriteFile(path, data, 0o644); err != nil {
		t.Fatal(err)
	}
	suite, hash, err := LoadSuite(path)
	if err != nil || suite.ID != "fixture" || len(hash) != 71 {
		t.Fatalf("suite=%#v hash=%q err=%v", suite, hash, err)
	}
}

func TestLoadSuiteRejectsUnknownFields(t *testing.T) {
	path := filepath.Join(t.TempDir(), "suite.json")
	data := []byte(`{
		"schema":"ygg.benchmark.suite/v1",
		"id":"fixture",
		"unknown":true,
		"repos":[],
		"cases":[]
	}`)
	if err := os.WriteFile(path, data, 0o644); err != nil {
		t.Fatal(err)
	}
	if _, _, err := LoadSuite(path); err == nil {
		t.Fatal("expected unknown suite field to fail")
	}
}

func TestLoadBenchmarkConfigHashesResolvedExecutables(t *testing.T) {
	binary := filepath.Join(t.TempDir(), "extractor")
	if err := os.WriteFile(binary, []byte("fixture"), 0o755); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "plugins.json")
	data := []byte(`{
		"schema":"ygg.config/v1",
		"plugins":[{
			"id":"fixture","version":"1","command":["` + binary + `"],
			"includeGlobs":["**/*.go"]
		}]
	}`)
	if err := os.WriteFile(path, data, 0o644); err != nil {
		t.Fatal(err)
	}
	resolved, hash, evidence, embedding, err := loadBenchmarkConfig(path)
	if err != nil || len(resolved) == 0 || len(hash) != 71 || len(evidence) != 1 {
		t.Fatalf("resolved=%q hash=%q evidence=%#v err=%v", resolved, hash, evidence, err)
	}
	if embedding != nil {
		t.Fatalf("embedding=%#v", embedding)
	}
	if evidence[0].Binary != binary || len(evidence[0].BinaryHash) != 71 {
		t.Fatalf("evidence=%#v", evidence)
	}
}

func TestLoadBenchmarkConfigRecordsEmbeddingCommandFiles(t *testing.T) {
	interpreter := filepath.Join(t.TempDir(), "python")
	worker := filepath.Join(t.TempDir(), "worker.py")
	for _, path := range []string{interpreter, worker} {
		if err := os.WriteFile(path, []byte("fixture"), 0o755); err != nil {
			t.Fatal(err)
		}
	}
	path := filepath.Join(t.TempDir(), "embedding.json")
	data := []byte(`{
		"schema":"ygg.config/v1",
		"embedding":{
			"kind":"command","command":["` + interpreter + `","` + worker + `"],
			"model":"fixture","dimensions":2
		}
	}`)
	if err := os.WriteFile(path, data, 0o644); err != nil {
		t.Fatal(err)
	}
	resolved, _, plugins, embedding, err := loadBenchmarkConfig(path)
	if err != nil || len(resolved) == 0 || len(plugins) != 0 || embedding == nil {
		t.Fatalf("resolved=%q plugins=%#v embedding=%#v err=%v", resolved, plugins, embedding, err)
	}
	if len(embedding.CommandFiles) != 2 ||
		embedding.CommandFiles[0].Path != interpreter ||
		embedding.CommandFiles[1].Path != worker {
		t.Fatalf("embedding=%#v", embedding)
	}
}

func TestInstallBenchmarkConfigDoesNotOverwriteCheckout(t *testing.T) {
	root := t.TempDir()
	cleanup, err := installBenchmarkConfig(root, []byte("{}\n"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(root, ".ygg", "config.json")); err != nil {
		t.Fatal(err)
	}
	if err := cleanup(); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(root, ".ygg")); !os.IsNotExist(err) {
		t.Fatalf("temporary directory remains: %v", err)
	}
	if err := os.MkdirAll(filepath.Join(root, ".ygg"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, ".ygg", "config.json"), []byte("tracked"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := installBenchmarkConfig(root, []byte("{}\n")); err == nil {
		t.Fatal("expected existing config refusal")
	}
}

func TestPercentileUsesNearestRank(t *testing.T) {
	values := []float64{4, 1, 3, 2}
	if got := percentile(values, 0.50); got != 2 {
		t.Fatalf("p50=%v", got)
	}
	if got := percentile(values, 0.95); got != 4 {
		t.Fatalf("p95=%v", got)
	}
}

func TestTrackedClaimSuiteHasBroadManualCoverage(t *testing.T) {
	suite, _, err := LoadSuite(filepath.Join("..", "..", "benchmarks", "claim-quick.json"))
	if err != nil {
		t.Fatal(err)
	}
	got := coverage(suite)
	if got.Cases < 10 || got.Repositories < 6 {
		t.Fatalf("coverage=%#v", got)
	}
	if len(got.ProblemClasses) < 3 || len(got.ArchitectureClasses) < 3 {
		t.Fatalf("manual class coverage=%#v", got)
	}
}

func TestTrackedDogfoodSuiteHasManualCoverage(t *testing.T) {
	suite, _, err := LoadSuite(filepath.Join("..", "..", "benchmarks", "dogfood-replay.json"))
	if err != nil {
		t.Fatal(err)
	}
	got := coverage(suite)
	if got.Cases != 8 || got.Repositories != 2 {
		t.Fatalf("coverage=%#v", got)
	}
	if len(got.SourceKinds) < 3 || len(got.ProblemClasses) < 3 ||
		len(got.ArchitectureClasses) < 3 {
		t.Fatalf("manual class coverage=%#v", got)
	}
}

func TestAggregateIncludesRawBaselineTiming(t *testing.T) {
	got := aggregate([]CaseReport{
		{RawFileRecallAt10: 0.5, RawMRR: 0.25, RawRipgrepMS: 4},
		{RawFileRecallAt10: 1, RawMRR: 0.5, RawRipgrepMS: 8},
	})
	if got.RawRecallAt10 != 0.75 || got.RawMRR != 0.375 ||
		got.RawSearchP50MS != 4 || got.RawSearchP95MS != 8 {
		t.Fatalf("aggregate=%#v", got)
	}
}

func TestParseRipgrepCountsSupportsNewlinesInPaths(t *testing.T) {
	got, err := parseRipgrepCounts([]byte("normal.go\x003\nodd\nname.go\x002\n"))
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 2 || got[0].path != "normal.go" || got[0].count != 3 ||
		got[1].path != "odd\nname.go" || got[1].count != 2 {
		t.Fatalf("scores=%#v", got)
	}
}
