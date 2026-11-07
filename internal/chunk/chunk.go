// Package chunk creates bounded, project-agnostic search records.
package chunk

import (
	"fmt"
	"maps"
	"strings"
	"unicode/utf8"

	"github.com/coadan/yggdrasil/internal/contracts"
	"github.com/coadan/yggdrasil/internal/discovery"
)

const (
	MaxLines = 160
	MaxBytes = 8 * 1024
	Overlap  = 20

	// Embedding windows are small enough for a short embedding context, so
	// text past a chunk's first tokens still gets a vector. A file keeps at most
	// WindowsPerChunk windows per lexical chunk, spread evenly over its window
	// grid, which bounds the embedding cost per chunk.
	WindowLines     = 30
	WindowBytes     = 1000
	WindowOverlap   = 5
	WindowsPerChunk = 3
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
	if len(lines) <= WindowLines && len(file.Content) <= WindowBytes {
		return appendRanges(records, file.Path, lines, MaxLines, MaxBytes, Overlap, nil, true)
	}
	before := len(records)
	records = appendRanges(records, file.Path, lines, MaxLines, MaxBytes, Overlap,
		map[string]any{"semantic": false}, true)
	windows := appendRanges(nil, file.Path, lines, WindowLines, WindowBytes, WindowOverlap,
		map[string]any{"lexical": false}, false)
	return append(records, spread(windows, WindowsPerChunk*(len(records)-before))...)
}

// spread keeps at most limit records, evenly spaced and including the first
// and last.
func spread(records []contracts.SearchRecord, limit int) []contracts.SearchRecord {
	if len(records) <= limit {
		return records
	}
	if limit < 2 {
		return records[:limit]
	}
	kept := make([]contracts.SearchRecord, limit)
	for i := range kept {
		kept[i] = records[i*(len(records)-1)/(limit-1)]
	}
	return kept
}

// appendRanges cuts lines into overlapping records of at most maxLines lines
// and maxBytes bytes. Lines longer than MaxBytes end a range; with splitLong
// they become lexical-only fragments, otherwise they are skipped.
func appendRanges(records []contracts.SearchRecord, path string, lines []string,
	maxLines, maxBytes, overlap int, metadata map[string]any, splitLong bool) []contracts.SearchRecord {
	for start := 0; start < len(lines); {
		if len(lines[start]) > MaxBytes {
			if splitLong {
				for _, text := range splitLongLine(lines[start]) {
					records = append(records, contracts.SearchRecord{
						Path:      path,
						StartLine: start + 1,
						EndLine:   start + 1,
						Kind:      "text-chunk",
						Title:     fmt.Sprintf("%s:%d", path, start+1),
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
			}
			start++
			continue
		}
		end, size := start+1, len(lines[start])
		for end < len(lines) && end < start+maxLines && size+1+len(lines[end]) <= maxBytes {
			size += 1 + len(lines[end])
			end++
		}
		text := strings.TrimSpace(strings.Join(lines[start:end], "\n"))
		if text != "" {
			records = append(records, contracts.SearchRecord{
				Path:      path,
				StartLine: start + 1,
				EndLine:   end,
				Kind:      "text-chunk",
				Title:     fmt.Sprintf("%s:%d", path, start+1),
				Text:      text,
				Source:    "core",
				Metadata:  maps.Clone(metadata),
			})
		}
		if end == len(lines) {
			break
		}
		next := end - overlap
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
