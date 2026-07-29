// Command ygg-extract-terraform emits HCL parser facts through the
// Yggdrasil extractor JSONL protocol.
package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"strings"
	"unicode"

	"github.com/hashicorp/hcl/v2"
	"github.com/hashicorp/hcl/v2/hclsyntax"
)

const (
	schema     = "ygg.extractor/v1"
	maxRecords = 256
)

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
	ID        string         `json:"id"`
	StartLine int            `json:"startLine"`
	EndLine   int            `json:"endLine"`
	Kind      string         `json:"kind"`
	Title     string         `json:"title"`
	Text      string         `json:"text"`
	Metadata  map[string]any `json:"metadata,omitempty"`
}

type diagnostic struct {
	Message string `json:"message"`
}

func main() {
	if err := run(os.Stdin, os.Stdout); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func run(input io.Reader, output io.Writer) error {
	scanner := bufio.NewScanner(input)
	scanner.Buffer(make([]byte, 64*1024), 8*1024*1024)
	encoder := json.NewEncoder(output)
	files, recordCount := 0, 0
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
			records, diagnostics := extract(value.File.Path, value.File.Content)
			files++
			recordCount += len(records)
			if err := encoder.Encode(map[string]any{
				"type": "result", "requestId": value.RequestID,
				"records": records, "diagnostics": diagnostics,
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

func extract(path, content string) ([]record, []diagnostic) {
	source := []byte(content)
	file, parsed := hclsyntax.ParseConfig(source, path, hcl.InitialPos)
	diagnostics := make([]diagnostic, 0, len(parsed))
	for _, item := range parsed {
		diagnostics = append(diagnostics, diagnostic{Message: item.Error()})
	}
	if file == nil {
		return nil, diagnostics
	}
	body, ok := file.Body.(*hclsyntax.Body)
	if !ok {
		return nil, append(diagnostics, diagnostic{Message: "unexpected HCL body"})
	}
	var records []record
	var visit func(*hclsyntax.Body, []string)
	visit = func(body *hclsyntax.Body, context []string) {
		for _, block := range body.Blocks {
			parts := append(append([]string{}, context...), block.Type)
			parts = append(parts, block.Labels...)
			title := strings.Join(parts, ".")
			blockRange := hcl.Range{
				Filename: path,
				Start:    block.TypeRange.Start,
				End:      block.OpenBraceRange.End,
			}
			records = append(records, makeRecord(
				"terraform-block", title, blockRange, source,
				map[string]any{"blockType": block.Type, "labels": block.Labels},
			))
			visit(block.Body, parts)
		}
		for name, attribute := range body.Attributes {
			title := strings.Join(append(append([]string{}, context...), name), ".")
			references := traversalReferences(attribute.Expr.Variables(), source)
			metadata := map[string]any{"attribute": name}
			if len(references) > 0 {
				metadata["references"] = references
			}
			records = append(records, makeRecord(
				"terraform-attribute", title, attribute.Range(), source, metadata,
			))
		}
	}
	visit(body, nil)
	if len(records) > maxRecords {
		records = records[:maxRecords]
	}
	return records, diagnostics
}

func traversalReferences(traversals []hcl.Traversal, source []byte) []string {
	seen := map[string]bool{}
	var result []string
	for _, traversal := range traversals {
		value := sourceText(source, traversal.SourceRange())
		if value == "" || seen[value] {
			continue
		}
		seen[value] = true
		result = append(result, value)
	}
	return result
}

func makeRecord(kind, title string, sourceRange hcl.Range, source []byte, metadata map[string]any) record {
	text := sourceText(source, sourceRange)
	if words := identifierWords(title); words != "" {
		text += "\n" + words
	}
	return record{
		ID:        fmt.Sprintf("%s:%d:%s", kind, sourceRange.Start.Line, title),
		StartLine: sourceRange.Start.Line,
		EndLine:   max(sourceRange.Start.Line, sourceRange.End.Line),
		Kind:      kind,
		Title:     title,
		Text:      strings.TrimSpace(text),
		Metadata:  metadata,
	}
}

func sourceText(source []byte, sourceRange hcl.Range) string {
	start, end := sourceRange.Start.Byte, sourceRange.End.Byte
	if start < 0 || end < start || end > len(source) {
		return ""
	}
	return strings.TrimSpace(string(source[start:end]))
}

func identifierWords(value string) string {
	var words []string
	var current []rune
	flush := func() {
		if len(current) > 0 {
			words = append(words, strings.ToLower(string(current)))
			current = nil
		}
	}
	for _, character := range value {
		switch {
		case character == '_' || character == '-' || character == '.':
			flush()
		case unicode.IsUpper(character) && len(current) > 0 && unicode.IsLower(current[len(current)-1]):
			flush()
			current = append(current, character)
		default:
			current = append(current, character)
		}
	}
	flush()
	return strings.Join(words, " ")
}
