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
			"id":"case","repositoryId":"repo","revision":"0123456789",
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
