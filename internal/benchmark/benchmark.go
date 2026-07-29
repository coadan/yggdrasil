// Package benchmark owns the developer-only, replayable search benchmark lane.
package benchmark

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"time"
	"unicode"
)

const SuiteSchema = "ygg.benchmark.suite/v1"
const ReportSchema = "ygg.benchmark.report/v1"

type Suite struct {
	Schema      string       `json:"schema"`
	ID          string       `json:"id"`
	Description string       `json:"description"`
	Repos       []Repository `json:"repos"`
	Cases       []Case       `json:"cases"`
}

type Repository struct {
	ID  string `json:"id"`
	URL string `json:"url"`
}

type Case struct {
	ID                  string   `json:"id"`
	RepositoryID        string   `json:"repositoryId"`
	Revision            string   `json:"revision"`
	FixRevision         string   `json:"fixRevision"`
	SourceURL           string   `json:"sourceUrl"`
	Query               string   `json:"query"`
	ExpectedPaths       []string `json:"expectedPaths"`
	SourceKinds         []string `json:"sourceKinds"`
	ProblemClasses      []string `json:"problemClasses"`
	ArchitectureClasses []string `json:"architectureClasses"`
}

type Options struct {
	SuitePath  string
	ReposDir   string
	WorkDir    string
	Binary     string
	Prepare    bool
	CheckOnly  bool
	Iterations int
	CaseIDs    []string
}

type Report struct {
	Schema      string       `json:"schema"`
	SuiteID     string       `json:"suiteId"`
	SuiteHash   string       `json:"suiteHash"`
	Binary      string       `json:"binary"`
	BinaryHash  string       `json:"binaryHash"`
	GeneratedAt string       `json:"generatedAt"`
	Platform    string       `json:"platform"`
	CheckOnly   bool         `json:"checkOnly"`
	Environment Environment  `json:"environment"`
	Coverage    Coverage     `json:"coverage"`
	Aggregate   Aggregate    `json:"aggregate"`
	Cases       []CaseReport `json:"cases"`
}

type Environment struct {
	GoVersion      string `json:"goVersion"`
	CPUs           int    `json:"cpus"`
	GitVersion     string `json:"gitVersion"`
	RipgrepVersion string `json:"ripgrepVersion"`
}

type Coverage struct {
	Repositories        int      `json:"repositories"`
	Cases               int      `json:"cases"`
	SourceKinds         []string `json:"sourceKinds"`
	ProblemClasses      []string `json:"problemClasses"`
	ArchitectureClasses []string `json:"architectureClasses"`
}

type Aggregate struct {
	FileRecallAt10 float64 `json:"fileRecallAt10"`
	MRR            float64 `json:"mrr"`
	NoiseAt20      float64 `json:"noiseAt20"`
	CitationRate   float64 `json:"citationRate"`
	FullIndexP50MS float64 `json:"fullIndexP50Ms"`
	NoopIndexP50MS float64 `json:"noopIndexP50Ms"`
	OneFileP50MS   float64 `json:"oneFileIncrementalP50Ms"`
	SearchP50MS    float64 `json:"searchP50Ms"`
	SearchP95MS    float64 `json:"searchP95Ms"`
	RawRecallAt10  float64 `json:"rawFileRecallAt10"`
	RawMRR         float64 `json:"rawMrr"`
	RawSearchP50MS float64 `json:"rawSearchP50Ms"`
	RawSearchP95MS float64 `json:"rawSearchP95Ms"`
}

type CaseReport struct {
	ID                   string    `json:"id"`
	RepositoryID         string    `json:"repositoryId"`
	Revision             string    `json:"revision"`
	FixRevision          string    `json:"fixRevision"`
	SourceURL            string    `json:"sourceUrl"`
	ExpectedPaths        []string  `json:"expectedPaths"`
	ResultPaths          []string  `json:"resultPaths"`
	RawRipgrepPaths      []string  `json:"rawRipgrepPaths"`
	FileRecallAt10       float64   `json:"fileRecallAt10"`
	MRR                  float64   `json:"mrr"`
	NoiseAt20            float64   `json:"noiseAt20"`
	CitationRate         float64   `json:"citationRate"`
	RawFileRecallAt10    float64   `json:"rawFileRecallAt10"`
	RawMRR               float64   `json:"rawMrr"`
	RawRipgrepMS         float64   `json:"rawRipgrepMs"`
	FullIndexMS          float64   `json:"fullIndexMs"`
	NoopIndexMS          float64   `json:"noopIndexMs"`
	OneFileIncrementalMS float64   `json:"oneFileIncrementalMs"`
	SearchSamplesMS      []float64 `json:"searchSamplesMs"`
}

type searchRecord struct {
	Path      string `json:"path"`
	StartLine int    `json:"startLine"`
	EndLine   int    `json:"endLine"`
}

type cliEnvelope struct {
	OK   bool `json:"ok"`
	Data struct {
		Records []searchRecord `json:"records"`
	} `json:"data"`
	Error *struct {
		Message string `json:"message"`
	} `json:"error"`
}

func LoadSuite(path string) (Suite, string, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return Suite{}, "", err
	}
	var suite Suite
	if err := decodeStrictJSON(data, &suite); err != nil {
		return Suite{}, "", fmt.Errorf("decode suite: %w", err)
	}
	if err := suite.Validate(); err != nil {
		return Suite{}, "", err
	}
	sum := sha256.Sum256(data)
	return suite, "sha256:" + hex.EncodeToString(sum[:]), nil
}

func (s Suite) Validate() error {
	if s.Schema != SuiteSchema {
		return fmt.Errorf("unsupported suite schema %q", s.Schema)
	}
	if s.ID == "" || len(s.Cases) == 0 {
		return errors.New("suite id and cases are required")
	}
	repos := map[string]bool{}
	for _, repo := range s.Repos {
		if repo.ID == "" || repo.URL == "" || repos[repo.ID] {
			return fmt.Errorf("invalid or duplicate repository %q", repo.ID)
		}
		parsed, err := url.Parse(repo.URL)
		if err != nil || parsed.Scheme != "https" || parsed.Host == "" {
			return fmt.Errorf("repository %q requires an https URL", repo.ID)
		}
		repos[repo.ID] = true
	}
	cases := map[string]bool{}
	for _, item := range s.Cases {
		if item.ID == "" || cases[item.ID] || !repos[item.RepositoryID] {
			return fmt.Errorf("invalid case %q", item.ID)
		}
		cases[item.ID] = true
		if !fullSHA(item.Revision) || !fullSHA(item.FixRevision) ||
			strings.TrimSpace(item.Query) == "" || len(item.ExpectedPaths) == 0 ||
			len(item.SourceKinds) == 0 || len(item.ProblemClasses) == 0 ||
			len(item.ArchitectureClasses) == 0 {
			return fmt.Errorf("case %q lacks revisions, query, expected paths, or manual classes", item.ID)
		}
		source, err := url.Parse(item.SourceURL)
		if err != nil || source.Scheme != "https" || source.Host == "" {
			return fmt.Errorf("case %q requires an https sourceUrl", item.ID)
		}
		expected := map[string]bool{}
		for _, expectedPath := range item.ExpectedPaths {
			clean := filepath.ToSlash(filepath.Clean(expectedPath))
			if filepath.IsAbs(expectedPath) || clean == "." || clean == ".." ||
				strings.HasPrefix(clean, "../") || clean != expectedPath || expected[clean] {
				return fmt.Errorf("case %q has invalid or duplicate expected path %q", item.ID, expectedPath)
			}
			expected[clean] = true
		}
	}
	return nil
}

func Run(ctx context.Context, opts Options) (Report, error) {
	if opts.Iterations <= 0 {
		opts.Iterations = 5
	}
	suite, suiteHash, err := LoadSuite(opts.SuitePath)
	if err != nil {
		return Report{}, err
	}
	if len(opts.CaseIDs) > 0 {
		requested := make(map[string]bool, len(opts.CaseIDs))
		for _, id := range opts.CaseIDs {
			requested[id] = true
		}
		var selected []Case
		for _, item := range suite.Cases {
			if requested[item.ID] {
				selected = append(selected, item)
				delete(requested, item.ID)
			}
		}
		if len(requested) != 0 {
			return Report{}, fmt.Errorf("unknown benchmark cases: %s", strings.Join(sortedKeys(requested), ", "))
		}
		suite.Cases = selected
	}
	binary, err := filepath.Abs(opts.Binary)
	if err != nil {
		return Report{}, err
	}
	if info, err := os.Stat(binary); err != nil || info.IsDir() {
		return Report{}, fmt.Errorf("candidate binary is not executable: %s", binary)
	}
	opts.Binary = binary
	opts.ReposDir, err = filepath.Abs(opts.ReposDir)
	if err != nil {
		return Report{}, err
	}
	opts.WorkDir, err = filepath.Abs(opts.WorkDir)
	if err != nil {
		return Report{}, err
	}
	if err := os.MkdirAll(opts.ReposDir, 0o755); err != nil {
		return Report{}, err
	}
	if err := os.MkdirAll(opts.WorkDir, 0o755); err != nil {
		return Report{}, err
	}
	repos := make(map[string]Repository, len(suite.Repos))
	for _, repo := range suite.Repos {
		repos[repo.ID] = repo
	}
	report := Report{
		Schema: ReportSchema, SuiteID: suite.ID, SuiteHash: suiteHash,
		Binary: binary, GeneratedAt: time.Now().UTC().Format(time.RFC3339),
		Platform:    runtime.GOOS + "/" + runtime.GOARCH,
		CheckOnly:   opts.CheckOnly,
		Environment: Environment{GoVersion: runtime.Version(), CPUs: runtime.NumCPU()},
		Coverage:    coverage(suite),
	}
	report.Environment.GitVersion, err = toolVersion(ctx, "git")
	if err != nil {
		return Report{}, err
	}
	report.Environment.RipgrepVersion, err = toolVersion(ctx, "rg")
	if err != nil {
		return Report{}, err
	}
	report.BinaryHash, err = fileHash(binary)
	if err != nil {
		return Report{}, err
	}
	for _, item := range suite.Cases {
		repoRoot := filepath.Join(opts.ReposDir, item.ID)
		if opts.Prepare {
			if err := prepare(ctx, repos[item.RepositoryID], item, repoRoot); err != nil {
				return Report{}, fmt.Errorf("prepare %s: %w", item.ID, err)
			}
		}
		if err := verifyCheckout(ctx, repoRoot, item.Revision); err != nil {
			return Report{}, fmt.Errorf("%s: %w (run with -prepare)", item.ID, err)
		}
		if err := verifyGroundTruth(ctx, repoRoot, item); err != nil {
			return Report{}, fmt.Errorf("%s: %w (run with -prepare)", item.ID, err)
		}
		if opts.CheckOnly {
			continue
		}
		value, err := runCase(ctx, opts, item, repoRoot)
		if err != nil {
			return Report{}, fmt.Errorf("%s: %w", item.ID, err)
		}
		report.Cases = append(report.Cases, value)
	}
	report.Aggregate = aggregate(report.Cases)
	return report, nil
}

func prepare(ctx context.Context, repo Repository, item Case, root string) error {
	if _, err := os.Stat(filepath.Join(root, ".git")); errors.Is(err, os.ErrNotExist) {
		if err := os.MkdirAll(filepath.Dir(root), 0o755); err != nil {
			return err
		}
		if _, err := command(ctx, "", nil, "git", "clone", "--filter=blob:none", "--no-checkout", repo.URL, root); err != nil {
			return err
		}
	} else if err != nil {
		return err
	}
	if _, err := command(
		ctx, root, nil, "git", "fetch", "--depth=1", "origin", item.Revision, item.FixRevision,
	); err != nil {
		return err
	}
	_, err := command(ctx, root, nil, "git", "checkout", "--detach", "--force", item.Revision)
	return err
}

func verifyGroundTruth(ctx context.Context, root string, item Case) error {
	output, err := command(
		ctx, root, nil, "git", "diff", "--name-only", item.Revision, item.FixRevision, "--",
	)
	if err != nil {
		return errors.New("fix revision is missing")
	}
	changed := map[string]bool{}
	for _, path := range strings.Split(strings.TrimSpace(string(output)), "\n") {
		if path != "" {
			changed[filepath.ToSlash(path)] = true
		}
	}
	for _, expected := range item.ExpectedPaths {
		if !changed[expected] {
			return fmt.Errorf("ground-truth path %s is not changed by fixing revision", expected)
		}
	}
	return nil
}

func verifyCheckout(ctx context.Context, root, revision string) error {
	output, err := command(ctx, root, nil, "git", "rev-parse", "HEAD")
	if err != nil {
		return errors.New("benchmark checkout is missing")
	}
	actual := strings.TrimSpace(string(output))
	if actual != revision {
		return fmt.Errorf("checkout is at %s, want %s", actual, revision)
	}
	return nil
}

func runCase(ctx context.Context, opts Options, item Case, root string) (CaseReport, error) {
	stateRoot := filepath.Join(opts.WorkDir, item.ID, "state")
	if err := os.MkdirAll(stateRoot, 0o755); err != nil {
		return CaseReport{}, err
	}
	env := append(os.Environ(), "YGG_STORAGE_ROOT="+stateRoot)
	fullMS, _, err := timedCommand(ctx, root, env, opts.Binary, "index", "--root", root, "--full", "--no-embed", "--json")
	if err != nil {
		return CaseReport{}, err
	}
	noopMS, _, err := timedCommand(ctx, root, env, opts.Binary, "index", "--root", root, "--no-embed", "--json")
	if err != nil {
		return CaseReport{}, err
	}
	touchPath := filepath.Join(root, filepath.FromSlash(item.ExpectedPaths[0]))
	info, err := os.Stat(touchPath)
	if err != nil {
		return CaseReport{}, fmt.Errorf("ground-truth path: %w", err)
	}
	now := time.Now()
	if err := os.Chtimes(touchPath, now, now); err != nil {
		return CaseReport{}, err
	}
	incrementalMS, _, err := timedCommand(ctx, root, env, opts.Binary, "index", "--root", root, "--no-embed", "--json")
	if err != nil {
		return CaseReport{}, err
	}
	_ = os.Chtimes(touchPath, info.ModTime(), info.ModTime())

	var records []searchRecord
	var samples []float64
	for range opts.Iterations {
		elapsed, output, err := timedCommand(
			ctx, root, env, opts.Binary,
			"search", "--root", root, "--mode", "lexical", "--limit", "20", "--json", item.Query,
		)
		if err != nil {
			return CaseReport{}, err
		}
		var envelope cliEnvelope
		if err := json.Unmarshal(output, &envelope); err != nil {
			return CaseReport{}, fmt.Errorf("decode search output: %w", err)
		}
		if !envelope.OK {
			message := "search failed"
			if envelope.Error != nil {
				message = envelope.Error.Message
			}
			return CaseReport{}, errors.New(message)
		}
		records = envelope.Data.Records
		samples = append(samples, elapsed)
	}
	paths := uniquePaths(records)
	rawStarted := time.Now()
	rawPaths, err := ripgrepPaths(ctx, root, item.Query)
	rawMS := float64(time.Since(rawStarted).Microseconds()) / 1000
	if err != nil {
		return CaseReport{}, err
	}
	recall, mrr, noise := score(paths, item.ExpectedPaths)
	rawRecall, rawMRR, _ := score(rawPaths, item.ExpectedPaths)
	cited := 0
	for _, record := range records {
		if record.Path != "" && record.StartLine > 0 && record.EndLine >= record.StartLine {
			cited++
		}
	}
	citationRate := 0.0
	if len(records) > 0 {
		citationRate = float64(cited) / float64(len(records))
	}
	return CaseReport{
		ID: item.ID, RepositoryID: item.RepositoryID, Revision: item.Revision,
		FixRevision: item.FixRevision, SourceURL: item.SourceURL,
		ExpectedPaths: item.ExpectedPaths, ResultPaths: paths, RawRipgrepPaths: rawPaths,
		FileRecallAt10: recall, MRR: mrr, NoiseAt20: noise, CitationRate: citationRate,
		RawFileRecallAt10: rawRecall, RawMRR: rawMRR, RawRipgrepMS: rawMS,
		FullIndexMS: fullMS, NoopIndexMS: noopMS, OneFileIncrementalMS: incrementalMS,
		SearchSamplesMS: samples,
	}, nil
}

func timedCommand(ctx context.Context, dir string, env []string, name string, args ...string) (float64, []byte, error) {
	started := time.Now()
	output, err := command(ctx, dir, env, name, args...)
	return float64(time.Since(started).Microseconds()) / 1000, output, err
}

func command(ctx context.Context, dir string, env []string, name string, args ...string) ([]byte, error) {
	cmd := exec.CommandContext(ctx, name, args...)
	cmd.Dir = dir
	if env != nil {
		cmd.Env = env
	}
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		detail := strings.TrimSpace(stderr.String())
		if detail == "" {
			detail = strings.TrimSpace(stdout.String())
		}
		return nil, fmt.Errorf("%s %s: %w: %s", name, strings.Join(args, " "), err, detail)
	}
	return stdout.Bytes(), nil
}

func ripgrepPaths(ctx context.Context, root, query string) ([]string, error) {
	args := []string{"-l", "-i", "--no-messages", "--sort", "path", "--glob", "!.git/**"}
	for _, token := range queryTokens(query) {
		args = append(args, "-e", token)
	}
	if len(args) == 7 {
		return nil, nil
	}
	args = append(args, ".")
	output, err := command(ctx, root, nil, "rg", args...)
	if err != nil {
		// ripgrep exits 1 when it found no matches.
		if strings.Contains(err.Error(), "exit status 1") {
			return nil, nil
		}
		return nil, err
	}
	lines := strings.Split(strings.TrimSpace(string(output)), "\n")
	var paths []string
	for _, line := range lines {
		line = strings.TrimPrefix(filepath.ToSlash(line), "./")
		if line != "" {
			paths = append(paths, line)
		}
		if len(paths) == 20 {
			break
		}
	}
	return paths, nil
}

func queryTokens(query string) []string {
	seen := map[string]bool{}
	var result []string
	for _, field := range strings.Fields(strings.ToLower(query)) {
		field = strings.TrimFunc(field, func(value rune) bool {
			return !unicode.IsLetter(value) && !unicode.IsDigit(value) && value != '_' && value != '-'
		})
		if len(field) < 3 || seen[field] {
			continue
		}
		seen[field] = true
		result = append(result, field)
	}
	return result
}

func uniquePaths(records []searchRecord) []string {
	seen := map[string]bool{}
	var result []string
	for _, record := range records {
		if record.Path != "" && !seen[record.Path] {
			seen[record.Path] = true
			result = append(result, record.Path)
		}
	}
	return result
}

func score(paths, expected []string) (float64, float64, float64) {
	truth := map[string]bool{}
	for _, path := range expected {
		truth[filepath.ToSlash(path)] = true
	}
	found := map[string]bool{}
	firstRank := 0
	for i, path := range paths {
		if i >= 20 {
			break
		}
		if truth[filepath.ToSlash(path)] {
			if i < 10 {
				found[filepath.ToSlash(path)] = true
			}
			if firstRank == 0 {
				firstRank = i + 1
			}
		}
	}
	recall := float64(len(found)) / float64(len(truth))
	mrr := 0.0
	if firstRank > 0 {
		mrr = 1 / float64(firstRank)
	}
	considered := min(20, len(paths))
	noise := 0.0
	if considered > 0 {
		relevant := 0
		for _, path := range paths[:considered] {
			if truth[filepath.ToSlash(path)] {
				relevant++
			}
		}
		noise = float64(considered-relevant) / float64(considered)
	}
	return recall, mrr, noise
}

func coverage(suite Suite) Coverage {
	repos := map[string]bool{}
	kinds := map[string]bool{}
	problems := map[string]bool{}
	architectures := map[string]bool{}
	for _, item := range suite.Cases {
		repos[item.RepositoryID] = true
		for _, value := range item.SourceKinds {
			kinds[value] = true
		}
		for _, value := range item.ProblemClasses {
			problems[value] = true
		}
		for _, value := range item.ArchitectureClasses {
			architectures[value] = true
		}
	}
	return Coverage{
		Repositories: len(repos), Cases: len(suite.Cases),
		SourceKinds: sortedKeys(kinds), ProblemClasses: sortedKeys(problems),
		ArchitectureClasses: sortedKeys(architectures),
	}
}

func aggregate(cases []CaseReport) Aggregate {
	var result Aggregate
	var full, noop, incremental, search, raw []float64
	for _, item := range cases {
		result.FileRecallAt10 += item.FileRecallAt10
		result.MRR += item.MRR
		result.NoiseAt20 += item.NoiseAt20
		result.CitationRate += item.CitationRate
		result.RawRecallAt10 += item.RawFileRecallAt10
		result.RawMRR += item.RawMRR
		full = append(full, item.FullIndexMS)
		noop = append(noop, item.NoopIndexMS)
		incremental = append(incremental, item.OneFileIncrementalMS)
		search = append(search, item.SearchSamplesMS...)
		raw = append(raw, item.RawRipgrepMS)
	}
	count := float64(len(cases))
	if count > 0 {
		result.FileRecallAt10 /= count
		result.MRR /= count
		result.NoiseAt20 /= count
		result.CitationRate /= count
		result.RawRecallAt10 /= count
		result.RawMRR /= count
	}
	result.FullIndexP50MS = percentile(full, 0.50)
	result.NoopIndexP50MS = percentile(noop, 0.50)
	result.OneFileP50MS = percentile(incremental, 0.50)
	result.SearchP50MS = percentile(search, 0.50)
	result.SearchP95MS = percentile(search, 0.95)
	result.RawSearchP50MS = percentile(raw, 0.50)
	result.RawSearchP95MS = percentile(raw, 0.95)
	return result
}

func percentile(values []float64, quantile float64) float64 {
	if len(values) == 0 {
		return 0
	}
	copyValues := append([]float64(nil), values...)
	sort.Float64s(copyValues)
	rank := int(float64(len(copyValues))*quantile+0.999999999) - 1
	rank = max(0, min(rank, len(copyValues)-1))
	return copyValues[rank]
}

func sortedKeys(values map[string]bool) []string {
	result := make([]string, 0, len(values))
	for value := range values {
		result = append(result, value)
	}
	sort.Strings(result)
	return result
}

func fileHash(path string) (string, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	sum := sha256.Sum256(data)
	return "sha256:" + hex.EncodeToString(sum[:]), nil
}

func decodeStrictJSON(data []byte, target any) error {
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		return err
	}
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		if err == nil {
			return errors.New("multiple JSON values")
		}
		return err
	}
	return nil
}

func fullSHA(value string) bool {
	if len(value) != 40 {
		return false
	}
	_, err := hex.DecodeString(value)
	return err == nil
}

func toolVersion(ctx context.Context, name string) (string, error) {
	output, err := command(ctx, "", nil, name, "--version")
	if err != nil {
		return "", err
	}
	line, _, _ := strings.Cut(strings.TrimSpace(string(output)), "\n")
	return line, nil
}
