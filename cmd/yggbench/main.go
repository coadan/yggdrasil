// yggbench is a developer-only replay harness. It is intentionally separate
// from the public ygg command surface.
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/coadan/yggdrasil/internal/benchmark"
)

func main() {
	suite := flag.String("suite", "benchmarks/claim-quick.json", "tracked benchmark suite")
	repos := flag.String("repos", ".dev/bench/repos", "prepared checkout directory")
	work := flag.String("work", ".dev/bench/work", "generated state directory")
	binary := flag.String("ygg", "bin/ygg", "candidate ygg binary")
	pluginConfig := flag.String("plugin-config", "", "optional extractor-only benchmark config")
	out := flag.String("out", ".dev/bench/report.json", "report output")
	prepare := flag.Bool("prepare", false, "clone and pin missing benchmark checkouts")
	checkOnly := flag.Bool("check-only", false, "verify revisions and ground truth without running searches")
	iterations := flag.Int("iterations", 5, "fresh-process searches per case")
	caseIDs := flag.String("cases", "", "optional comma-separated case ids")
	flag.Parse()
	report, err := benchmark.Run(context.Background(), benchmark.Options{
		SuitePath: *suite, ReposDir: *repos, WorkDir: *work, Binary: *binary,
		PluginConfigPath: *pluginConfig,
		Prepare:          *prepare, CheckOnly: *checkOnly,
		Iterations: *iterations, CaseIDs: splitIDs(*caseIDs),
	})
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	data, err := json.MarshalIndent(report, "", "  ")
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	if err := os.MkdirAll(filepath.Dir(*out), 0o755); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	data = append(data, '\n')
	if err := os.WriteFile(*out, data, 0o644); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	if report.CheckOnly {
		fmt.Printf("%d cases verified\n", report.Coverage.Cases)
		return
	}
	fmt.Printf(
		"%d cases: recall@10 %.3f, MRR %.3f, search p50 %.2f ms, p95 %.2f ms\n",
		len(report.Cases), report.Aggregate.FileRecallAt10, report.Aggregate.MRR,
		report.Aggregate.SearchP50MS, report.Aggregate.SearchP95MS,
	)
}

func splitIDs(value string) []string {
	var result []string
	for _, id := range strings.Split(value, ",") {
		if id = strings.TrimSpace(id); id != "" {
			result = append(result, id)
		}
	}
	return result
}
