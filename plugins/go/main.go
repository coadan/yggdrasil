// Command ygg-extract-go emits parser-owned Go declarations and imports
// through the Yggdrasil extractor JSONL protocol.
package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"go/ast"
	"go/parser"
	"go/token"
	"io"
	"os"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"
)

const (
	schema     = "ygg.extractor/v1"
	maxRecords = 256
	maxText    = 64*1024 - 1
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
	ID        string `json:"id"`
	StartLine int    `json:"startLine"`
	EndLine   int    `json:"endLine"`
	Kind      string `json:"kind"`
	Title     string `json:"title,omitempty"`
	Text      string `json:"text"`
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
	files := token.NewFileSet()
	tree, err := parser.ParseFile(files, path, content, parser.AllErrors)
	var diagnostics []diagnostic
	if err != nil {
		diagnostics = append(diagnostics, diagnostic{Message: err.Error()})
	}
	if tree == nil {
		return nil, diagnostics
	}
	var records []record
	add := func(value record) {
		if len(records) < maxRecords {
			value.Text = truncate(value.Text)
			records = append(records, value)
		}
	}
	for _, declaration := range tree.Decls {
		switch value := declaration.(type) {
		case *ast.FuncDecl:
			kind := "go-function"
			if value.Recv != nil {
				kind = "go-method"
			}
			end := value.End()
			if value.Body != nil {
				end = value.Body.Lbrace
			}
			startLine := files.Position(value.Pos()).Line
			endLine := files.Position(end).Line
			add(record{
				ID:        fmt.Sprintf("%s:%d:%s", kind, startLine, value.Name.Name),
				StartLine: startLine, EndLine: endLine, Kind: kind, Title: value.Name.Name,
				Text: sourceText(files, content, value.Pos(), end) + "\n" + identifierWords(value.Name.Name),
			})
		case *ast.GenDecl:
			for _, spec := range value.Specs {
				switch item := spec.(type) {
				case *ast.ImportSpec:
					title, unquoteErr := strconv.Unquote(item.Path.Value)
					if unquoteErr != nil {
						title = item.Path.Value
					}
					startLine := files.Position(item.Pos()).Line
					endLine := files.Position(item.End()).Line
					add(record{
						ID:        fmt.Sprintf("go-import:%d:%s", startLine, title),
						StartLine: startLine, EndLine: endLine, Kind: "go-import", Title: title,
						Text: sourceText(files, content, item.Pos(), item.End()),
					})
				case *ast.TypeSpec:
					line := files.Position(item.Pos()).Line
					add(record{
						ID:        fmt.Sprintf("go-type:%d:%s", line, item.Name.Name),
						StartLine: line, EndLine: line, Kind: "go-type", Title: item.Name.Name,
						Text: lineText(content, line) + "\n" + identifierWords(item.Name.Name),
					})
				case *ast.ValueSpec:
					for _, name := range item.Names {
						line := files.Position(name.Pos()).Line
						kind := "go-" + value.Tok.String()
						add(record{
							ID:        fmt.Sprintf("%s:%d:%s", kind, line, name.Name),
							StartLine: line, EndLine: line, Kind: kind, Title: name.Name,
							Text: lineText(content, line) + "\n" + identifierWords(name.Name),
						})
					}
				}
			}
		}
	}
	return records, diagnostics
}

func sourceText(files *token.FileSet, content string, start, end token.Pos) string {
	startOffset := files.Position(start).Offset
	endOffset := files.Position(end).Offset
	if startOffset < 0 || endOffset < startOffset || endOffset > len(content) {
		return ""
	}
	return strings.TrimSpace(content[startOffset:endOffset])
}

func lineText(content string, line int) string {
	lines := strings.Split(content, "\n")
	if line < 1 || line > len(lines) {
		return ""
	}
	return strings.TrimSpace(lines[line-1])
}

func identifierWords(value string) string {
	var words []string
	start := 0
	runes := []rune(value)
	flush := func(end int) {
		if end > start {
			words = append(words, strings.ToLower(string(runes[start:end])))
		}
		start = end
	}
	for index, current := range runes {
		if current == '_' || current == '-' {
			flush(index)
			start = index + 1
			continue
		}
		if index > start && unicode.IsUpper(current) &&
			(unicode.IsLower(runes[index-1]) ||
				(index+1 < len(runes) && unicode.IsLower(runes[index+1]))) {
			flush(index)
		}
	}
	flush(len(runes))
	return strings.Join(words, " ")
}

func truncate(value string) string {
	if len(value) <= maxText {
		return value
	}
	value = value[:maxText]
	for !utf8.ValidString(value) {
		value = value[:len(value)-1]
	}
	return value
}
