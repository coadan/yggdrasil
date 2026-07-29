// Command ygg-extract-markdown emits mechanical Markdown heading and fence
// records through the Yggdrasil extractor JSONL protocol.
package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"regexp"
	"strings"
	"unicode/utf8"
)

const schema = "ygg.extractor/v1"

var headingPattern = regexp.MustCompile(`^(#{1,6})[ \t]+(.+?)[ \t]*#*[ \t]*$`)

type message struct {
	Type      string `json:"type"`
	Schema    string `json:"schema,omitempty"`
	RequestID string `json:"requestId,omitempty"`
	File      struct {
		Path    string `json:"path"`
		Content string `json:"content"`
	} `json:"file,omitempty"`
}

type record struct {
	ID        string `json:"id"`
	StartLine int    `json:"startLine"`
	EndLine   int    `json:"endLine"`
	Kind      string `json:"kind"`
	Title     string `json:"title,omitempty"`
	Text      string `json:"text"`
}

func main() {
	if err := run(os.Stdin, os.Stdout); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func run(input *os.File, output *os.File) error {
	scanner := bufio.NewScanner(input)
	scanner.Buffer(make([]byte, 64*1024), 8*1024*1024)
	encoder := json.NewEncoder(output)
	files := 0
	recordCount := 0
	for scanner.Scan() {
		var value message
		if err := json.Unmarshal(scanner.Bytes(), &value); err != nil {
			return fmt.Errorf("decode input: %w", err)
		}
		switch value.Type {
		case "hello":
			if value.Schema != schema {
				return fmt.Errorf("unsupported schema %q", value.Schema)
			}
			if err := encoder.Encode(map[string]any{"type": "ready", "schema": schema}); err != nil {
				return err
			}
		case "file":
			records := extract(value.File.Content)
			files++
			recordCount += len(records)
			if err := encoder.Encode(map[string]any{
				"type":        "result",
				"requestId":   value.RequestID,
				"records":     records,
				"diagnostics": []any{},
			}); err != nil {
				return err
			}
		case "end":
			return encoder.Encode(map[string]any{
				"type": "summary", "files": files, "records": recordCount,
			})
		default:
			return fmt.Errorf("unsupported message type %q", value.Type)
		}
	}
	return scanner.Err()
}

func extract(content string) []record {
	lines := strings.Split(content, "\n")
	var result []record
	for line := 0; line < len(lines) && len(result) < 256; {
		if match := headingPattern.FindStringSubmatch(lines[line]); match != nil {
			end := line + 1
			for end < len(lines) && headingPattern.FindStringSubmatch(lines[end]) == nil {
				end++
			}
			result = append(result, record{
				ID:        fmt.Sprintf("heading:%d", line+1),
				StartLine: line + 1,
				EndLine:   max(line+1, end),
				Kind:      "markdown-section",
				Title:     strings.TrimSpace(match[2]),
				Text:      truncate(strings.TrimSpace(strings.Join(lines[line:end], "\n"))),
			})
			line++
			continue
		}
		trimmed := strings.TrimSpace(lines[line])
		marker := fenceMarker(trimmed)
		if marker != "" {
			end := line + 1
			for end < len(lines) && !strings.HasPrefix(strings.TrimSpace(lines[end]), marker) {
				end++
			}
			if end < len(lines) {
				end++
			}
			title := strings.TrimSpace(strings.TrimPrefix(trimmed, marker))
			result = append(result, record{
				ID:        fmt.Sprintf("fence:%d", line+1),
				StartLine: line + 1,
				EndLine:   max(line+1, end),
				Kind:      "markdown-fence",
				Title:     title,
				Text:      truncate(strings.TrimSpace(strings.Join(lines[line:end], "\n"))),
			})
			line = max(line+1, end)
			continue
		}
		line++
	}
	return result
}

func fenceMarker(line string) string {
	switch {
	case strings.HasPrefix(line, "```"):
		return "```"
	case strings.HasPrefix(line, "~~~"):
		return "~~~"
	default:
		return ""
	}
}

func truncate(value string) string {
	const maxBytes = 64*1024 - 1
	if len(value) <= maxBytes {
		return value
	}
	value = value[:maxBytes]
	for !utf8.ValidString(value) {
		value = value[:len(value)-1]
	}
	return value
}
