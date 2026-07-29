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

	"github.com/coadan/yggdrasil/internal/config"
	"github.com/coadan/yggdrasil/internal/contracts"
	"github.com/coadan/yggdrasil/internal/discovery"
	"github.com/coadan/yggdrasil/internal/indexer"
	"github.com/coadan/yggdrasil/internal/plugin"
	"github.com/coadan/yggdrasil/internal/project"
	"github.com/coadan/yggdrasil/internal/search"
	"github.com/coadan/yggdrasil/internal/status"
	"github.com/coadan/yggdrasil/internal/store"
)

const usage = `Usage:
  ygg version
  ygg index [--root PATH] [--full] [--no-embed] [--json]
  ygg search [--root PATH] [--limit N] [--mode auto|lexical|semantic] [--json] QUERY
  ygg status [--root PATH] [--json]
  ygg plugin check <plugin-id> [--root PATH] [--file RELATIVE_PATH] [--json]
`

var Version = "0.3.0-dev"

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
		return r.fail(false, 2, errors.New("plugin id is required before plugin check flags"))
	}
	pluginID := args[0]
	flags := flag.NewFlagSet("plugin check", flag.ContinueOnError)
	flags.SetOutput(r.stderr)
	root := flags.String("root", "", "repository root")
	filePath := flags.String("file", "", "relative file to extract")
	jsonOutput := flags.Bool("json", false, "write JSON")
	if err := flags.Parse(args[1:]); err != nil {
		return 2
	}
	if flags.NArg() != 0 {
		return r.fail(*jsonOutput, 2, errors.New("plugin check accepts one plugin id"))
	}
	paths, cfg, err := resolve(*root)
	if err != nil {
		return r.fail(*jsonOutput, 2, err)
	}
	var pluginConfig *config.Plugin
	for i := range cfg.Plugins {
		if cfg.Plugins[i].ID == pluginID {
			pluginConfig = &cfg.Plugins[i]
			break
		}
	}
	if pluginConfig == nil {
		return r.fail(*jsonOutput, 2, fmt.Errorf("plugin %q is not configured", pluginID))
	}
	var file *discovery.File
	if *filePath != "" {
		rel := filepath.ToSlash(filepath.Clean(*filePath))
		if filepath.IsAbs(*filePath) || rel == ".." || strings.HasPrefix(rel, "../") {
			return r.fail(*jsonOutput, 2, errors.New("--file must stay within the repository root"))
		}
		info, err := os.Stat(filepath.Join(paths.Root, filepath.FromSlash(rel)))
		if err != nil {
			return r.fail(*jsonOutput, 2, err)
		}
		value, skipped, err := discovery.Read(paths.Root, discovery.Candidate{
			Path: rel, Size: info.Size(), MTimeNS: info.ModTime().UnixNano(),
		}, cfg.MaxFileBytes)
		if err != nil {
			return r.fail(*jsonOutput, 1, err)
		}
		if skipped != nil {
			return r.fail(*jsonOutput, 2, fmt.Errorf("%s: %s", skipped.Path, skipped.Reason))
		}
		file = &value
	}
	result, err := plugin.Check(ctx, paths.Root, *pluginConfig, file)
	if err != nil {
		return r.fail(*jsonOutput, 1, err)
	}
	if *jsonOutput {
		return r.writeJSON(envelope{Schema: contracts.CLIEnvelopeSchema, OK: true, Data: result})
	}
	fmt.Fprintf(r.stdout, "Plugin %s is ready", pluginID)
	if file != nil {
		fmt.Fprintf(r.stdout, "; %d records from %s", len(result.Records), file.Path)
	}
	fmt.Fprintln(r.stdout, ".")
	return 0
}

func (r *runner) runIndex(ctx context.Context, args []string) int {
	flags := flag.NewFlagSet("index", flag.ContinueOnError)
	flags.SetOutput(r.stderr)
	root := flags.String("root", "", "repository root")
	full := flags.Bool("full", false, "rebuild every file")
	noEmbed := flags.Bool("no-embed", false, "skip embeddings")
	jsonOutput := flags.Bool("json", false, "write JSON")
	if err := flags.Parse(args); err != nil {
		return 2
	}
	if flags.NArg() != 0 {
		return r.fail(*jsonOutput, 2, errors.New("index accepts no positional arguments"))
	}
	paths, cfg, err := resolve(*root)
	if err != nil {
		return r.fail(*jsonOutput, 2, err)
	}
	summary, err := indexer.Run(ctx, paths, cfg, indexer.Options{Full: *full, NoEmbed: *noEmbed})
	if err != nil {
		return r.fail(*jsonOutput, 1, err)
	}
	if *jsonOutput {
		return r.writeJSON(envelope{Schema: contracts.CLIEnvelopeSchema, OK: true, Data: summary})
	}
	fmt.Fprintf(r.stdout,
		"Indexed %d, unchanged %d, deleted %d, skipped %d in %d ms.\n",
		summary.Indexed, summary.Unchanged, summary.Deleted, summary.Skipped, summary.ElapsedMS)
	return 0
}

func (r *runner) runSearch(ctx context.Context, args []string) int {
	flags := flag.NewFlagSet("search", flag.ContinueOnError)
	flags.SetOutput(r.stderr)
	root := flags.String("root", "", "repository root")
	limit := flags.Int("limit", 10, "result limit")
	mode := flags.String("mode", "auto", "auto, lexical, or semantic")
	jsonOutput := flags.Bool("json", false, "write JSON")
	if err := flags.Parse(args); err != nil {
		return 2
	}
	query := strings.TrimSpace(strings.Join(flags.Args(), " "))
	if query == "" {
		return r.fail(*jsonOutput, 2, errors.New("search query is required"))
	}
	if *limit < 1 || *limit > search.MaxResults {
		return r.fail(*jsonOutput, 2, fmt.Errorf("search limit must be between 1 and %d", search.MaxResults))
	}
	paths, cfg, err := resolve(*root)
	if err != nil {
		return r.fail(*jsonOutput, 2, err)
	}
	if _, err := os.Stat(paths.Database); errors.Is(err, os.ErrNotExist) {
		seeds, seedErr := project.SiblingIndexes(ctx, paths)
		if seedErr != nil {
			return r.fail(*jsonOutput, 1, fmt.Errorf("find worktree index: %w", seedErr))
		}
		if len(seeds) == 0 {
			return r.fail(*jsonOutput, 3, errors.New("repository is not indexed; run ygg index"))
		}
		if _, seedErr := indexer.Run(ctx, paths, cfg, indexer.Options{}); seedErr != nil {
			return r.fail(*jsonOutput, 1, fmt.Errorf("prepare worktree index: %w", seedErr))
		}
	} else if err != nil {
		return r.fail(*jsonOutput, 1, err)
	}
	value, err := store.Open(ctx, paths.Database, paths.Root, paths.ID)
	if err != nil {
		return r.fail(*jsonOutput, 1, err)
	}
	defer value.Close()
	result, err := search.Run(ctx, value, query, search.Options{
		Mode: *mode, Limit: *limit, Root: paths.Root,
		HasExtractors: len(cfg.Plugins) > 0, Embedding: cfg.Embedding,
	})
	if errors.Is(err, search.ErrSemanticUnavailable) {
		return r.fail(*jsonOutput, 3, err)
	}
	if err != nil {
		return r.fail(*jsonOutput, 1, err)
	}
	if *jsonOutput {
		return r.writeJSON(envelope{Schema: contracts.CLIEnvelopeSchema, OK: true, Data: result})
	}
	if result.FallbackReason != "" {
		fmt.Fprintf(r.stderr, "Search mode: %s (%s).\n", result.ActiveMode, result.FallbackReason)
	}
	for _, record := range result.Records {
		fmt.Fprintf(r.stdout, "%s:%d-%d\t%s\n", record.Path, record.StartLine, record.EndLine, record.Excerpt)
	}
	return 0
}

func (r *runner) runStatus(ctx context.Context, args []string) int {
	flags := flag.NewFlagSet("status", flag.ContinueOnError)
	flags.SetOutput(r.stderr)
	root := flags.String("root", "", "repository root")
	jsonOutput := flags.Bool("json", false, "write JSON")
	if err := flags.Parse(args); err != nil {
		return 2
	}
	if flags.NArg() != 0 {
		return r.fail(*jsonOutput, 2, errors.New("status accepts no positional arguments"))
	}
	paths, cfg, err := resolve(*root)
	if err != nil {
		return r.fail(*jsonOutput, 2, err)
	}
	result, err := status.Inspect(ctx, paths, cfg)
	if err != nil {
		return r.fail(*jsonOutput, 1, err)
	}
	if *jsonOutput {
		return r.writeJSON(envelope{Schema: contracts.CLIEnvelopeSchema, OK: true, Data: result})
	}
	if !result.Indexed {
		fmt.Fprintf(r.stdout, "Not indexed: %s (%d files discovered).\n", result.Root, result.Freshness.New)
		return 0
	}
	fmt.Fprintf(r.stdout,
		"%s: %d files, %d records; drift +%d ~%d -%d.\n",
		result.Root, result.Counts.Files, result.Counts.Records,
		result.Freshness.New, result.Freshness.Modified, result.Freshness.Deleted)
	return 0
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
