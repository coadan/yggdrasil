// Package chunk creates bounded, project-agnostic search records.
package chunk

import (
	"fmt"
	"strings"

	"github.com/coadan/yggdrasil/internal/contracts"
	"github.com/coadan/yggdrasil/internal/discovery"
)

const (
	MaxLines = 160
	MaxBytes = 8 * 1024
	Overlap  = 20
)

func Records(file discovery.File) []contracts.SearchRecord {
	records := []contracts.SearchRecord{{
		Path:      file.Path,
		StartLine: 1,
		EndLine:   1,
		Kind:      "file",
		Title:     file.Path,
		Text:      file.Path,
		Source:    "core",
	}}
	lines := strings.Split(file.Content, "\n")
	for start := 0; start < len(lines); {
		end := min(start+MaxLines, len(lines))
		for end > start+1 && len(strings.Join(lines[start:end], "\n")) > MaxBytes {
			end--
		}
		text := strings.TrimSpace(strings.Join(lines[start:end], "\n"))
		if text != "" {
			records = append(records, contracts.SearchRecord{
				Path:      file.Path,
				StartLine: start + 1,
				EndLine:   end,
				Kind:      "text-chunk",
				Title:     fmt.Sprintf("%s:%d", file.Path, start+1),
				Text:      text,
				Source:    "core",
			})
		}
		if end == len(lines) {
			break
		}
		next := end - Overlap
		if next <= start {
			next = end
		}
		start = next
	}
	return records
}
