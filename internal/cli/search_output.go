package cli

import (
	"fmt"
	"io"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"

	"github.com/coadan/yggdrasil/internal/contracts"
	"github.com/coadan/yggdrasil/query"
)

func (r *runner) writeSearchResult(result query.Result, format string) int {
	if format == "json" {
		return r.writeJSON(envelope{Schema: contracts.CLIEnvelopeSchema, OK: true, Data: result})
	}
	var output strings.Builder
	fmt.Fprintf(&output, "%d results (%s)\n", len(result.Records), result.ActiveMode)
	for _, record := range result.Records {
		fmt.Fprintf(&output, "%s:%d-%d\n", compactPath(record.Path), record.StartLine, record.EndLine)
		if record.Excerpt != "" {
			for line := range strings.SplitSeq(strings.TrimSuffix(record.Excerpt, "\n"), "\n") {
				fmt.Fprintf(&output, "    %s\n", compactExcerptLine(strings.TrimSuffix(line, "\r")))
			}
		}
	}
	if len(result.MorePaths) != 0 {
		output.WriteString("more: ")
		for index, path := range result.MorePaths {
			if index != 0 {
				output.WriteString(", ")
			}
			output.WriteString(compactPath(path))
		}
		output.WriteByte('\n')
	}
	if _, err := io.WriteString(r.stdout, output.String()); err != nil {
		fmt.Fprintln(r.stderr, "ygg: write compact search result:", err)
		return 1
	}
	return 0
}

func compactPath(path string) string {
	quoted := strconv.Quote(path)
	// Quote line controls, separators, and paths that could look like excerpts
	// or an overflow marker. Ordinary repository paths stay directly readable.
	if quoted[1:len(quoted)-1] != path || strings.ContainsRune(path, ',') ||
		strings.TrimSpace(path) != path || strings.HasPrefix(path, "more: ") {
		return quoted
	}
	return path
}

func compactExcerptLine(line string) string {
	if utf8.ValidString(line) && strings.IndexFunc(line, func(r rune) bool {
		return r != '\t' && !unicode.IsPrint(r)
	}) == -1 {
		return line
	}
	quoted := strconv.Quote(line)
	return quoted[1 : len(quoted)-1]
}
