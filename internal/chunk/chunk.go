// Package chunk creates bounded, project-agnostic search records.
package chunk

import (
	"fmt"
	"strings"
	"unicode"
	"unicode/utf8"

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
		if len(lines[start]) > MaxBytes {
			for _, text := range splitLongLine(lines[start]) {
				records = append(records, contracts.SearchRecord{
					Path:      file.Path,
					StartLine: start + 1,
					EndLine:   start + 1,
					Kind:      "text-chunk",
					Title:     fmt.Sprintf("%s:%d", file.Path, start+1),
					Text:      text,
					Source:    "core",
					// A byte ceiling cannot bound provider tokens for minified data.
					// Keep these fragments available to lexical search without making
					// configured embeddings fail the entire repository.
					// ponytail: embed long-line fragments when providers accept a
					// deterministic token budget in the embedding protocol.
					Metadata: map[string]any{"semantic": false},
				})
			}
			start++
			continue
		}
		end := min(start+MaxLines, len(lines))
		for end > start+1 && len(strings.Join(lines[start:end], "\n")) > MaxBytes {
			end--
		}
		if strings.TrimSpace(strings.Join(lines[start:end], "\n")) != "" {
			// Blank edge lines are dropped and the cited lines skip them too:
			// line i of Text is file line StartLine+i, indentation included,
			// so excerpts keep code aligned.
			first, last := start, end
			for strings.TrimSpace(lines[first]) == "" {
				first++
			}
			for strings.TrimSpace(lines[last-1]) == "" {
				last--
			}
			text := strings.TrimRightFunc(strings.Join(lines[first:last], "\n"), unicode.IsSpace)
			records = append(records, contracts.SearchRecord{
				Path:      file.Path,
				StartLine: first + 1,
				EndLine:   last,
				Kind:      "text-chunk",
				Title:     fmt.Sprintf("%s:%d", file.Path, first+1),
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

func splitLongLine(line string) []string {
	var result []string
	for len(line) > MaxBytes {
		end := MaxBytes
		for end > 0 && !utf8.RuneStart(line[end]) {
			end--
		}
		if text := strings.TrimSpace(line[:end]); text != "" {
			result = append(result, text)
		}
		line = line[end:]
	}
	if text := strings.TrimSpace(line); text != "" {
		result = append(result, text)
	}
	return result
}
