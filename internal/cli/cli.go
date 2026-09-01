// Package cli owns the complete public command surface.
package cli

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	embeddingcontract "github.com/coadan/yggdrasil/embedding"
	"github.com/coadan/yggdrasil/internal/config"
	"github.com/coadan/yggdrasil/internal/contracts"
	"github.com/coadan/yggdrasil/internal/discovery"
	"github.com/coadan/yggdrasil/internal/embedding"
	"github.com/coadan/yggdrasil/internal/indexer"
	"github.com/coadan/yggdrasil/internal/plugin"
	"github.com/coadan/yggdrasil/internal/project"
	"github.com/coadan/yggdrasil/internal/status"
	"github.com/coadan/yggdrasil/internal/store"
	search "github.com/coadan/yggdrasil/query"
)

const usage = `Usage:
  ygg version
  ygg index [--root PATH] [--full] [--no-embed]
  ygg search [--limit N] [--mode auto|lexical|semantic|graph] [-F|-E] [--about TEXT] PATTERN [PATH]
  ygg status [--root PATH] [--check]
  ygg plugin check <plugin-id> [--root PATH] [--file RELATIVE_PATH]
`

var Version = "0.3.0-dev"

const searchIndexGrace = 100 * time.Millisecond

var errSearchIndexBusy = errors.New("index is still running")

type envelope struct {
	Schema string     `json:"schema"`
	OK     bool       `json:"ok"`
	Data   any        `json:"data,omitempty"`
	Error  *errorData `json:"error,omitempty"`
}

type errorData struct {
	Message string `json:"message"`
}

type runner struct {
	stdout io.Writer
	stderr io.Writer
}

func Main(ctx context.Context, args []string, stdout, stderr io.Writer) int {
	return (&runner{stdout: stdout, stderr: stderr}).run(ctx, args)
}

func (r *runner) run(ctx context.Context, args []string) int {
	if len(args) == 0 || args[0] == "help" || args[0] == "--help" || args[0] == "-h" {
		fmt.Fprint(r.stdout, usage)
		return 0
	}
	if args[0] == "--version" || args[0] == "-version" {
		if len(args) != 1 {
			return r.fail(false, 2, errors.New("version accepts no arguments"))
		}
		fmt.Fprintf(r.stdout, "ygg %s\n", Version)
		return 0
	}
	switch args[0] {
	case "version":
		if len(args) != 1 {
			return r.fail(false, 2, errors.New("version accepts no arguments"))
		}
		fmt.Fprintf(r.stdout, "ygg %s\n", Version)
		return 0
	case "index":
		return r.runIndex(ctx, args[1:])
	case "search":
		return r.runSearch(ctx, args[1:])
	case "status":
		return r.runStatus(ctx, args[1:])
	case "plugin":
		if len(args) > 1 && args[1] == "check" {
			return r.runPluginCheck(ctx, args[2:])
		}
		return r.fail(false, 2, errors.New("usage: ygg plugin check <plugin-id>"))
	default:
		return r.fail(false, 2, fmt.Errorf("unknown command %q", args[0]))
	}
}

func (r *runner) runPluginCheck(ctx context.Context, args []string) int {
	if len(args) == 0 || strings.HasPrefix(args[0], "-") {
		return r.fail(true, 2, errors.New("plugin id is required before plugin check flags"))
	}
	pluginID := args[0]
	args = discardJSONAssertion(args)
	flags, flagOutput := newOperationalFlagSet("plugin check")
	root := flags.String("root", "", "repository root")
	filePath := flags.String("file", "", "relative file to extract")
	if err := flags.Parse(args[1:]); err != nil {
		return r.flagParseResult(flagOutput, err)
	}
	if flags.NArg() != 0 {
		return r.fail(true, 2, errors.New("plugin check accepts one plugin id"))
	}
	paths, cfg, err := resolve(*root)
	if err != nil {
		return r.fail(true, 2, err)
	}
	var pluginConfig *config.Plugin
	for i := range cfg.Plugins {
		if cfg.Plugins[i].ID == pluginID {
			pluginConfig = &cfg.Plugins[i]
			break
		}
	}
	if pluginConfig == nil {
		return r.fail(true, 2, fmt.Errorf("plugin %q is not configured", pluginID))
	}
	var file *discovery.File
	if *filePath != "" {
		rel := filepath.ToSlash(filepath.Clean(*filePath))
		if filepath.IsAbs(*filePath) || rel == ".." || strings.HasPrefix(rel, "../") {
			return r.fail(true, 2, errors.New("--file must stay within the repository root"))
		}
		info, err := os.Stat(filepath.Join(paths.Root, filepath.FromSlash(rel)))
		if err != nil {
			return r.fail(true, 2, err)
		}
		value, skipped, err := discovery.Read(paths.Root, discovery.Candidate{
			Path: rel, Size: info.Size(), MTimeNS: info.ModTime().UnixNano(),
		}, cfg.MaxFileBytes)
		if err != nil {
			return r.fail(true, 1, err)
		}
		if skipped != nil {
			return r.fail(true, 2, fmt.Errorf("%s: %s", skipped.Path, skipped.Reason))
		}
		file = &value
	}
	result, err := plugin.Check(ctx, paths.Root, *pluginConfig, file)
	if err != nil {
		return r.fail(true, 1, err)
	}
	return r.writeJSON(envelope{Schema: contracts.CLIEnvelopeSchema, OK: true, Data: result})
}

func (r *runner) runIndex(ctx context.Context, args []string) int {
	args = discardJSONAssertion(args)
	flags, flagOutput := newOperationalFlagSet("index")
	root := flags.String("root", "", "repository root")
	full := flags.Bool("full", false, "rebuild every file")
	noEmbed := flags.Bool("no-embed", false, "skip embeddings")
	if err := flags.Parse(args); err != nil {
		return r.flagParseResult(flagOutput, err)
	}
	if flags.NArg() != 0 {
		return r.fail(true, 2, errors.New("index accepts no positional arguments"))
	}
	paths, cfg, err := resolve(*root)
	if err != nil {
		return r.fail(true, 2, err)
	}
	progress := newProgressReporter(r.stderr)
	summary, err := indexer.Run(ctx, paths, cfg, indexer.Options{
		Full:             *full,
		NoEmbed:          *noEmbed,
		EnsureCurrent:    !*full,
		EnsureEmbeddings: !*noEmbed,
		Progress:         progress.Report,
	})
	if err != nil {
		return r.fail(true, 1, err)
	}
	return r.writeJSON(envelope{Schema: contracts.CLIEnvelopeSchema, OK: true, Data: summary})
}

func (r *runner) runSearch(ctx context.Context, args []string) int {
	args = discardJSONAssertion(args)
	flags, flagOutput := newOperationalFlagSet("search")
	root := flags.String("root", "", "repository root, directory, or file scope")
	limit := flags.Int("limit", 10, "result limit")
	mode := flags.String("mode", "auto", "auto, lexical, semantic, or graph")
	fixed := false
	regexpPattern := false
	flags.BoolVar(&fixed, "F", false, "match the lexical pattern as a fixed string")
	flags.BoolVar(&fixed, "fixed-strings", false, "match the lexical pattern as a fixed string")
	flags.BoolVar(&regexpPattern, "E", false, "match the lexical pattern as a regular expression")
	flags.BoolVar(&regexpPattern, "regexp", false, "match the lexical pattern as a regular expression")
	about := flags.String("about", "", "semantic intent used independently of the lexical pattern")
	flags.Usage = func() {
		fmt.Fprintf(flagOutput, "Usage of %s:\n", flags.Name())
		flags.PrintDefaults()
		fmt.Fprint(
			flagOutput,
			"\nJSON output paths:\n  result records: data.records\n"+
				"  additional candidate paths, when present: data.morePaths\n",
		)
	}
	if err := parseInterspersed(flags, args); err != nil {
		if strings.Contains(err.Error(), "flag provided but not defined: -path") {
			err = errors.New("--path is not supported; use --root PATH for repository, directory, or file scope")
		} else if strings.Contains(err.Error(), "flag provided but not defined:") {
			err = fmt.Errorf("%w; place -- before a query that begins with '-'", err)
		}
		return r.flagParseResult(flagOutput, err)
	}
	positionals := flags.Args()
	if *root == "" && len(positionals) > 2 {
		return r.fail(true, 2, errors.New(
			"search accepts PATTERN and optional PATH; quote a multiword pattern",
		))
	}
	if fixed && regexpPattern {
		return r.fail(true, 2, errors.New("--fixed-strings and --regexp are mutually exclusive"))
	}
	matchKind := search.MatchText
	if fixed {
		matchKind = search.MatchFixed
	} else if regexpPattern {
		matchKind = search.MatchRegexp
	}
	query := ""
	resolvedRoot := *root
	if *root != "" {
		query = strings.TrimSpace(strings.Join(positionals, " "))
	} else if len(positionals) > 0 {
		query = strings.TrimSpace(positionals[0])
		if len(positionals) == 2 {
			resolvedRoot = positionals[1]
		}
	}
	if query == "" {
		return r.fail(true, 2, errors.New("search query is required"))
	}
	if *limit < 1 || *limit > search.MaxResults {
		return r.fail(true, 2, fmt.Errorf("search limit must be between 1 and %d", search.MaxResults))
	}
	started := time.Now()
	preflightPlan, err := search.Parse(query, matchKind, *about, "")
	if err != nil {
		return r.fail(true, 2, err)
	}
	if *mode == "semantic" && preflightPlan.Semantic == nil {
		return r.fail(true, 2, errors.New("regexp has no semantic text; use --about"))
	}
	paths, cfg, err := resolve(resolvedRoot)
	if err != nil {
		return r.fail(true, 2, err)
	}
	progress := newProgressReporter(r.stderr)
	indexLock, value, err := prepareSearchIndex(
		ctx, paths, cfg, *mode != "lexical", progress.Report,
	)
	if errors.Is(err, errSearchIndexBusy) && (*mode == "auto" || *mode == "lexical") {
		result, fallbackErr := search.RunFilesystem(ctx, paths.Root, query, search.FilesystemOptions{
			Limit: *limit, Scope: paths.Scope, IgnoreGlobs: cfg.IgnoreGlobs,
			MaxFileBytes: cfg.MaxFileBytes, RequestedMode: *mode,
			MatchKind: matchKind, About: *about,
		})
		if fallbackErr != nil {
			return r.fail(true, 1, fmt.Errorf("search live working tree: %w", fallbackErr))
		}
		result.ElapsedMS = time.Since(started).Milliseconds()
		return r.writeJSON(envelope{Schema: contracts.CLIEnvelopeSchema, OK: true, Data: result})
	}
	if err != nil {
		return r.fail(true, 1, err)
	}
	defer releaseSearchIndexLock(indexLock)
	defer value.Close()
	var capability *embeddingcontract.Capability
	var lazy *lazyEmbeddingProvider
	if cfg.Embedding != nil {
		lazy = &lazyEmbeddingProvider{root: paths.Root, config: *cfg.Embedding}
		defer lazy.Close()
		capability = &embeddingcontract.Capability{
			Provider: lazy, ProviderFingerprint: "cli-compatibility",
			IndexFingerprint: embedding.Fingerprint(*cfg.Embedding),
			Model:            cfg.Embedding.Model, Dimensions: cfg.Embedding.Dimensions,
			QueryPrefix:    cfg.Embedding.QueryPrefix,
			DocumentPrefix: cfg.Embedding.DocumentPrefix,
			BatchSize:      cfg.Embedding.BatchSize, MaxInputChars: cfg.Embedding.MaxInputChars,
		}
	}
	result, err := search.Run(ctx, value, query, search.Options{
		Mode: *mode, Limit: *limit, Scope: paths.Scope,
		MatchKind: matchKind, About: *about,
		HasExtractors: len(cfg.Plugins) > 0, Embedding: capability,
	})
	if errors.Is(err, search.ErrSemanticUnavailable) {
		return r.fail(true, 3, err)
	}
	if err != nil {
		return r.fail(true, 1, err)
	}
	result.ElapsedMS = time.Since(started).Milliseconds()
	return r.writeJSON(envelope{Schema: contracts.CLIEnvelopeSchema, OK: true, Data: result})
}

func prepareSearchIndex(
	ctx context.Context,
	paths project.Paths,
	cfg config.Config,
	ensureEmbeddings bool,
	progress func(indexer.Progress),
) (*os.File, *store.Store, error) {
	embeddingAttempted := false
	for range 3 {
		indexLock, err := acquireSearchIndexLock(ctx, paths.IndexLock, searchIndexGrace)
		if err != nil {
			return nil, nil, err
		}
		if _, err := os.Stat(paths.Database); errors.Is(err, os.ErrNotExist) {
			releaseSearchIndexLock(indexLock)
		} else if err != nil {
			releaseSearchIndexLock(indexLock)
			return nil, nil, err
		} else {
			value, openErr := store.Open(ctx, paths.Database, paths.Root, paths.ID)
			if openErr != nil {
				releaseSearchIndexLock(indexLock)
				return nil, nil, openErr
			}
			current, tokenErr := indexer.FreshnessToken(ctx, paths.Root, cfg)
			if tokenErr != nil {
				value.Close()
				releaseSearchIndexLock(indexLock)
				return nil, nil, tokenErr
			}
			indexed, tokenErr := value.IndexFreshnessToken(ctx)
			if tokenErr != nil {
				value.Close()
				releaseSearchIndexLock(indexLock)
				return nil, nil, tokenErr
			}
			ready := current == indexed
			if ready && ensureEmbeddings && cfg.Embedding != nil && !embeddingAttempted {
				state, stateErr := value.EmbeddingState(
					ctx, embedding.Fingerprint(*cfg.Embedding),
				)
				if stateErr != nil {
					value.Close()
					releaseSearchIndexLock(indexLock)
					return nil, nil, stateErr
				}
				ready = state.Complete
			}
			if ready {
				return indexLock, value, nil
			}
			value.Close()
			releaseSearchIndexLock(indexLock)
		}
		if _, err := indexer.Run(ctx, paths, cfg, indexer.Options{
			EnsureEmbeddings: ensureEmbeddings, Progress: progress,
		}); errors.Is(err, indexer.ErrIndexBusy) {
			return nil, nil, errSearchIndexBusy
		} else if err != nil {
			return nil, nil, fmt.Errorf("refresh repository index: %w", err)
		}
		embeddingAttempted = ensureEmbeddings && cfg.Embedding != nil
	}
	return nil, nil, errors.New("repository changed repeatedly during index refresh; retry search")
}

func parseInterspersed(flags *flag.FlagSet, args []string) error {
	options := make([]string, 0, len(args))
	positional := make([]string, 0, len(args))
	afterTerminator := false
	for index := 0; index < len(args); index++ {
		arg := args[index]
		if afterTerminator {
			positional = append(positional, arg)
			continue
		}
		if arg == "--" {
			afterTerminator = true
			continue
		}
		if arg == "-" || !strings.HasPrefix(arg, "-") {
			positional = append(positional, arg)
			continue
		}

		options = append(options, arg)
		name := strings.TrimLeft(arg, "-")
		if separator := strings.IndexByte(name, '='); separator >= 0 {
			name = name[:separator]
		}
		value := flags.Lookup(name)
		if value == nil || strings.Contains(arg, "=") {
			continue
		}
		boolValue, isBool := value.Value.(interface{ IsBoolFlag() bool })
		if isBool && boolValue.IsBoolFlag() {
			continue
		}
		if index+1 < len(args) {
			index++
			options = append(options, args[index])
		}
	}
	return flags.Parse(append(append(options, "--"), positional...))
}

// discardJSONAssertion keeps already-running agents working while leaving the
// redundant format assertion out of the canonical help and parser surface.
func discardJSONAssertion(args []string) []string {
	result := make([]string, 0, len(args))
	afterTerminator := false
	for _, arg := range args {
		if arg == "--" {
			afterTerminator = true
		}
		if !afterTerminator && (arg == "--json" || arg == "-json") {
			continue
		}
		result = append(result, arg)
	}
	return result
}

func newOperationalFlagSet(name string) (*flag.FlagSet, *strings.Builder) {
	output := &strings.Builder{}
	flags := flag.NewFlagSet(name, flag.ContinueOnError)
	flags.SetOutput(output)
	return flags, output
}

func (r *runner) flagParseResult(output *strings.Builder, err error) int {
	if errors.Is(err, flag.ErrHelp) {
		fmt.Fprint(r.stderr, output.String())
		return 0
	}
	return r.fail(true, 2, err)
}

func acquireSearchIndexLock(ctx context.Context, path string, wait time.Duration) (*os.File, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return nil, fmt.Errorf("create index state directory: %w", err)
	}
	lock, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return nil, fmt.Errorf("open index lock: %w", err)
	}
	timer := time.NewTimer(wait)
	defer timer.Stop()
	ticker := time.NewTicker(10 * time.Millisecond)
	defer ticker.Stop()
	for {
		err = syscall.Flock(int(lock.Fd()), syscall.LOCK_SH|syscall.LOCK_NB)
		if err == nil {
			return lock, nil
		}
		if !errors.Is(err, syscall.EWOULDBLOCK) && !errors.Is(err, syscall.EAGAIN) {
			lock.Close()
			return nil, fmt.Errorf("lock index for search: %w", err)
		}
		select {
		case <-ctx.Done():
			lock.Close()
			return nil, ctx.Err()
		case <-timer.C:
			lock.Close()
			return nil, errSearchIndexBusy
		case <-ticker.C:
		}
	}
}

func releaseSearchIndexLock(lock *os.File) {
	if lock == nil {
		return
	}
	_ = syscall.Flock(int(lock.Fd()), syscall.LOCK_UN)
	_ = lock.Close()
}

func (r *runner) runStatus(ctx context.Context, args []string) int {
	args = discardJSONAssertion(args)
	flags, flagOutput := newOperationalFlagSet("status")
	root := flags.String("root", "", "repository root")
	check := flags.Bool("check", false, "check the configured embedding provider")
	if err := flags.Parse(args); err != nil {
		return r.flagParseResult(flagOutput, err)
	}
	if flags.NArg() != 0 {
		return r.fail(true, 2, errors.New("status accepts no positional arguments"))
	}
	paths, cfg, err := resolve(*root)
	if err != nil {
		return r.fail(true, 2, err)
	}
	result, err := status.Inspect(ctx, paths, cfg, status.Options{
		Version: Version, CheckProvider: *check,
	})
	if err != nil {
		return r.fail(true, 1, err)
	}
	return r.writeJSON(envelope{Schema: contracts.CLIEnvelopeSchema, OK: true, Data: result})
}

func resolve(explicitRoot string) (project.Paths, config.Config, error) {
	paths, err := project.Resolve(explicitRoot)
	if err != nil {
		return project.Paths{}, config.Config{}, err
	}
	cfg, err := config.Load(paths.Root)
	return paths, cfg, err
}

func (r *runner) fail(jsonOutput bool, code int, err error) int {
	if jsonOutput {
		return r.writeJSONWithCode(envelope{
			Schema: contracts.CLIEnvelopeSchema,
			OK:     false,
			Error:  &errorData{Message: err.Error()},
		}, code)
	}
	fmt.Fprintln(r.stderr, "ygg:", err)
	return code
}

func (r *runner) writeJSON(value envelope) int {
	return r.writeJSONWithCode(value, 0)
}

func (r *runner) writeJSONWithCode(value envelope, code int) int {
	encoder := json.NewEncoder(r.stdout)
	encoder.SetEscapeHTML(false)
	if err := encoder.Encode(value); err != nil {
		fmt.Fprintln(r.stderr, "ygg: encode JSON:", err)
		return 1
	}
	return code
}
