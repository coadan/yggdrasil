// Command ygg-extract-typescript emits mechanically scanned TypeScript
// declarations and module imports through the Yggdrasil extractor protocol.
package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"strings"
	"text/scanner"
	"unicode"
	"unicode/utf8"
)

const (
	schema             = "ygg.extractor/v1"
	maxFacts           = 256
	maxText            = 64*1024 - 1
	maxStructuralBytes = 512 * 1024
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
	Title     string         `json:"title,omitempty"`
	Text      string         `json:"text"`
	Metadata  map[string]any `json:"metadata,omitempty"`
}

type token struct {
	text string
	line int
}

func main() {
	if err := run(os.Stdin, os.Stdout); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func run(input io.Reader, output io.Writer) error {
	inputScanner := bufio.NewScanner(input)
	inputScanner.Buffer(make([]byte, 64*1024), 8*1024*1024)
	encoder := json.NewEncoder(output)
	files := 0
	recordCount := 0
	for inputScanner.Scan() {
		var value message
		if err := json.Unmarshal(inputScanner.Bytes(), &value); err != nil {
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
			records := extract(value.File.Path, value.File.Content)
			files++
			recordCount += len(records)
			if err := encoder.Encode(map[string]any{
				"type": "result", "requestId": value.RequestID,
				"records": records, "diagnostics": []any{},
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
	return inputScanner.Err()
}

func extract(path, content string) []record {
	tokens := scan(content)
	lines := strings.Split(content, "\n")
	var facts, structures []record
	structuralBytes := 0
	depth := 0
	for index := 0; index < len(tokens) && len(facts) < maxFacts; index++ {
		current := tokens[index]
		if current.text == "}" && depth > 0 {
			depth--
		}
		if depth == 0 && current.text == "import" {
			if moduleIndex := importModule(tokens, index); moduleIndex > index {
				module := trimString(tokens[moduleIndex].text)
				facts = append(facts, makeRecord(
					"typescript-import", module, current.line,
					lineText(lines, current.line)+"\n"+module,
				))
				index = moduleIndex
				continue
			}
		}
		if depth == 0 && current.text == "export" {
			if moduleIndex := exportModule(tokens, index); moduleIndex > index {
				module := trimString(tokens[moduleIndex].text)
				facts = append(facts, makeRecord(
					"typescript-export", module, current.line,
					lineText(lines, current.line)+"\n"+module,
				))
				index = moduleIndex
				continue
			}
		}
		if depth == 0 && declarationKind(current.text) != "" {
			if name := nextIdentifier(tokens, index+1); name.text != "" {
				kind := declarationKind(current.text)
				endLine := declarationEndLine(tokens, index)
				facts = append(facts, makeRecord(
					kind, name.text, current.line,
					lineText(lines, current.line)+"\n"+identifierWords(name.text),
				))
				if endLine > current.line && len(structures) < maxFacts {
					structure := makeStructuralRecord(
						kind, name.text, current.line, endLine,
						lineRangeText(lines, current.line, endLine)+"\n"+identifierWords(name.text),
					)
					if structuralBytes+len(structure.Text) <= maxStructuralBytes {
						structures = append(structures, structure)
						structuralBytes += len(structure.Text)
					}
				}
			}
		}
		if current.text == "{" {
			depth++
		}
	}
	records := append(facts, structures...)
	if len(facts) > 0 {
		records = append([]record{navigationRecord(path, facts)}, records...)
	}
	return records
}

func declarationEndLine(tokens []token, start int) int {
	braces := 0
	seenBrace := false
	for index := start; index < len(tokens); index++ {
		switch tokens[index].text {
		case "{":
			braces++
			seenBrace = true
		case "}":
			if braces > 0 {
				braces--
			}
			if seenBrace && braces == 0 {
				return tokens[index].line
			}
		case ";":
			if !seenBrace {
				return tokens[index].line
			}
		}
	}
	return tokens[start].line
}

func scan(content string) []token {
	var value scanner.Scanner
	value.Init(strings.NewReader(content))
	value.Filename = "input.ts"
	value.Mode = scanner.GoTokens
	value.Error = func(*scanner.Scanner, string) {}
	value.IsIdentRune = func(current rune, index int) bool {
		return current == '$' || current == '_' || unicode.IsLetter(current) ||
			(index > 0 && unicode.IsDigit(current))
	}
	var result []token
	for {
		kind := value.Scan()
		if kind == scanner.EOF {
			break
		}
		result = append(result, token{text: value.TokenText(), line: value.Position.Line})
	}
	return result
}

func importModule(tokens []token, start int) int {
	for index := start + 1; index < len(tokens); index++ {
		if tokens[index].text == ";" {
			return -1
		}
		if isQuoted(tokens[index].text) &&
			(index == start+1 || tokens[index-1].text == "from") {
			return index
		}
	}
	return -1
}

func exportModule(tokens []token, start int) int {
	for index := start + 1; index < len(tokens); index++ {
		if tokens[index].text == ";" {
			return -1
		}
		if tokens[index].text == "from" && index+1 < len(tokens) &&
			isQuoted(tokens[index+1].text) {
			return index + 1
		}
		if tokens[index].line > tokens[start].line && declarationKind(tokens[index].text) != "" {
			return -1
		}
	}
	return -1
}

func declarationKind(value string) string {
	switch value {
	case "function", "class", "interface", "type", "enum", "namespace", "const", "let", "var":
		return "typescript-" + value
	default:
		return ""
	}
}

func nextIdentifier(tokens []token, start int) token {
	if start >= len(tokens) {
		return token{}
	}
	value := tokens[start]
	if value.text == "{" || value.text == "[" || value.text == "(" ||
		!isIdentifier(value.text) {
		return token{}
	}
	return value
}

func isIdentifier(value string) bool {
	if value == "" {
		return false
	}
	for index, current := range value {
		if current != '$' && current != '_' && !unicode.IsLetter(current) &&
			(index == 0 || !unicode.IsDigit(current)) {
			return false
		}
	}
	return true
}

func isQuoted(value string) bool {
	return len(value) >= 2 &&
		((value[0] == '"' && value[len(value)-1] == '"') ||
			(value[0] == '\'' && value[len(value)-1] == '\'') ||
			(value[0] == '`' && value[len(value)-1] == '`'))
}

func trimString(value string) string {
	if isQuoted(value) {
		return value[1 : len(value)-1]
	}
	return value
}

func makeRecord(kind, title string, line int, text string) record {
	return record{
		ID:        kind + ":" + fmt.Sprint(line) + ":" + title,
		StartLine: line, EndLine: line, Kind: kind, Title: title, Text: truncate(text),
	}
}

func makeStructuralRecord(ownerKind, title string, startLine, endLine int, text string) record {
	value := makeRecord("typescript-structural", title, startLine, text)
	value.ID = ownerKind + "-structural:" + fmt.Sprint(startLine) + ":" + title
	value.EndLine = max(startLine, endLine)
	value.Metadata = map[string]any{
		"structural": true, "lexical": false, "ownerKind": ownerKind,
	}
	return value
}

func navigationRecord(path string, records []record) record {
	var text strings.Builder
	fmt.Fprintf(&text, "file %s\nkind typescript\n", path)
	for _, value := range records {
		fmt.Fprintf(&text, "%s %s\n", value.Kind, value.Title)
	}
	return record{
		ID: "typescript-navigation:1", StartLine: 1, EndLine: 1,
		Kind: "typescript-navigation", Title: path,
		Text:     truncate(strings.TrimSpace(text.String())),
		Metadata: map[string]any{"facts": len(records), "lexical": false},
	}
}

func lineText(lines []string, line int) string {
	if line < 1 || line > len(lines) {
		return ""
	}
	return strings.TrimSpace(lines[line-1])
}

func lineRangeText(lines []string, startLine, endLine int) string {
	if startLine < 1 || startLine > len(lines) || endLine < startLine {
		return ""
	}
	endLine = min(endLine, len(lines))
	return strings.TrimSpace(strings.Join(lines[startLine-1:endLine], "\n"))
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
