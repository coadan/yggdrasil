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
	"time"

	publicindex "github.com/coadan/yggdrasil/index"
	"github.com/coadan/yggdrasil/internal/config"
	"github.com/coadan/yggdrasil/internal/contracts"
	"github.com/coadan/yggdrasil/internal/discovery"
	"github.com/coadan/yggdrasil/internal/plugin"
	"github.com/coadan/yggdrasil/internal/project"
	"github.com/coadan/yggdrasil/internal/status"
	search "github.com/coadan/yggdrasil/query"
)

const usage = `Usage:
  ygg version
  ygg index [--root PATH] [--full] [--no-embed]
  ygg search [--limit N] [--mode auto|lexical|semantic|graph] [--format json|compact] [-F|-E] [--about TEXT] PATTERN [PATH]
  ygg status [--root PATH] [--check]
  ygg plugin check <plugin-id> [--root PATH] [--file RELATIVE_PATH]
`

var Version = "devel"

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
	repository, err := openCLIRepository(ctx, paths, cfg)
	if err != nil {
		return r.fail(true, 1, err)
	}
	progress := newProgressReporter(r.stderr)
	summary, err := repository.index.Refresh(ctx, publicindex.RefreshOptions{
		Full: *full, NoEmbed: *noEmbed, CompleteEmbeddings: !*noEmbed,
		WaitForWriter: true,
		Progress: func(value publicindex.Progress) {
			progress.report(value.Phase, value.Completed, value.Total, value)
		},
	})
	if closeErr := repository.Close(); err == nil && closeErr != nil {
		err = closeErr
	}
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
	format := flags.String("format", "json", "successful search output: json or compact")
	fixed := false
	regexpPattern := false
	flags.BoolVar(&fixed, "F", false, "match the lexical pattern as a fixed string")
	flags.BoolVar(&fixed, "fixed-strings", false, "match the lexical pattern as a fixed string")
	flags.BoolVar(&regexpPattern, "E", false, "match the lexical pattern as a regular expression")
	flags.BoolVar(&regexpPattern, "regexp", false, "match the lexical pattern as a regular expression")
	about := flags.String("about", "", "semantic intent used independently of the lexical pattern; "+
		"without a pattern, also the query")
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
	if *format != "json" && *format != "compact" {
		return r.fail(true, 2, fmt.Errorf("unsupported search format %q; use json or compact", *format))
	}
	positionals := flags.Args()
	if *root == "" && len(positionals) > 2 {
		return r.fail(true, 2, errors.New(
			"search accepts PATTERN and optional PATH; quote a multiword pattern",
		))
	}
	if fixed && regexpPattern {
		return r.fail(true, 2, errors.New(
			"-F (--fixed-strings) and -E (--regexp) are mutually exclusive: "+
				"pass one pattern, -F for a literal string or -E for a regular expression; "+
				"combine alternatives in one pattern such as -E 'first|second'",
		))
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
	if query == "" && !regexpPattern {
		// A description alone is a complete query: search its words and meaning.
		query = strings.TrimSpace(*about)
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
	repository, err := openCLIRepository(ctx, paths, cfg)
	if err != nil {
		return r.fail(true, 1, err)
	}
	defer repository.Close()
	progress := newProgressReporter(r.stderr)
	snapshot, err := repository.index.OpenSnapshot(ctx)
	if !publicindex.IsBusy(err) &&
		(errors.Is(err, publicindex.ErrUnavailable) || errors.Is(err, publicindex.ErrStale)) {
		_, err = repository.index.Refresh(ctx, publicindex.RefreshOptions{
			NoEmbed: true,
			Progress: func(value publicindex.Progress) {
				progress.report(value.Phase, value.Completed, value.Total, value)
			},
		})
		if err == nil {
			snapshot, err = repository.index.OpenSnapshot(ctx)
		}
	}
	if publicindex.IsBusy(err) && (*mode == "auto" || *mode == "lexical") {
		result, fallbackErr := search.RunFilesystem(ctx, paths.Root, query, search.FilesystemOptions{
			Limit: *limit, Scope: paths.Scope, IgnoreGlobs: cfg.IgnoreGlobs,
			MaxFileBytes: cfg.MaxFileBytes, RequestedMode: *mode,
			MatchKind: matchKind, About: *about,
		})
		if fallbackErr != nil {
			return r.fail(true, 1, fmt.Errorf("search live working tree: %w", fallbackErr))
		}
		result.ElapsedMS = time.Since(started).Milliseconds()
		return r.writeSearchResult(result, *format)
	}
	if err != nil {
		return r.fail(true, 1, err)
	}
	defer snapshot.Close()
	rerank, lazyRerank := lazyRerankCapability(paths.Root, cfg.Rerank)
	if lazyRerank != nil {
		defer lazyRerank.Close()
	}
	result, err := search.Run(ctx, snapshot, query, search.Options{
		Mode: *mode, Limit: *limit, Scope: paths.Scope,
		MatchKind: matchKind, About: *about,
		HasExtractors: len(cfg.Plugins) > 0, Embedding: repository.index.Embedding(),
		Rerank: rerank,
	})
	if errors.Is(err, search.ErrSemanticUnavailable) {
		return r.fail(true, 3, err)
	}
	if err != nil {
		return r.fail(true, 1, err)
	}
	result.ElapsedMS = time.Since(started).Milliseconds()
	return r.writeSearchResult(result, *format)
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
		if arg == "-" || !strings.HasPrefix(arg, "-") || multiwordDash(arg) {
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

// multiwordDash reports a dash-led argument whose would-be flag name contains
// whitespace, such as a quoted "--force graph overwrite" query. No flag name
// contains whitespace, so it can only be a query.
func multiwordDash(arg string) bool {
	name := strings.TrimLeft(arg, "-")
	if separator := strings.IndexByte(name, '='); separator >= 0 {
		name = name[:separator]
	}
	return strings.ContainsAny(name, " \t\r\n")
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
