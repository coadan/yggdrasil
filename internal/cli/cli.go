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
	"strings"

	"github.com/coadan/yggdrasil/internal/config"
	"github.com/coadan/yggdrasil/internal/contracts"
	"github.com/coadan/yggdrasil/internal/indexer"
	"github.com/coadan/yggdrasil/internal/project"
	"github.com/coadan/yggdrasil/internal/search"
	"github.com/coadan/yggdrasil/internal/status"
	"github.com/coadan/yggdrasil/internal/store"
)

const usage = `Usage:
  ygg index [--root PATH] [--full] [--no-embed] [--json]
  ygg search [--root PATH] [--limit N] [--mode auto|lexical|semantic] [--json] QUERY
  ygg status [--root PATH] [--json]
  ygg plugin check <plugin-id> [--root PATH] [--file RELATIVE_PATH] [--json]
`

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
	switch args[0] {
	case "index":
		return r.runIndex(ctx, args[1:])
	case "search":
		return r.runSearch(ctx, args[1:])
	case "status":
		return r.runStatus(ctx, args[1:])
	case "plugin":
		if len(args) > 1 && args[1] == "check" {
			return r.fail(false, 1, errors.New("plugin check is not available until an extractor is configured"))
		}
		return r.fail(false, 2, errors.New("usage: ygg plugin check <plugin-id>"))
	default:
		return r.fail(false, 2, fmt.Errorf("unknown command %q", args[0]))
	}
}

func (r *runner) runIndex(ctx context.Context, args []string) int {
	flags := flag.NewFlagSet("index", flag.ContinueOnError)
	flags.SetOutput(r.stderr)
	root := flags.String("root", "", "repository root")
	full := flags.Bool("full", false, "rebuild every file")
	_ = flags.Bool("no-embed", false, "skip embeddings")
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
	summary, err := indexer.Run(ctx, paths, cfg, indexer.Options{Full: *full})
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
	paths, _, err := resolve(*root)
	if err != nil {
		return r.fail(*jsonOutput, 2, err)
	}
	if _, err := os.Stat(paths.Database); errors.Is(err, os.ErrNotExist) {
		return r.fail(*jsonOutput, 3, errors.New("repository is not indexed; run ygg index"))
	} else if err != nil {
		return r.fail(*jsonOutput, 1, err)
	}
	value, err := store.Open(ctx, paths.Database, paths.Root, paths.ID)
	if err != nil {
		return r.fail(*jsonOutput, 1, err)
	}
	defer value.Close()
	result, err := search.Run(ctx, value, query, search.Options{Mode: *mode, Limit: *limit})
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
