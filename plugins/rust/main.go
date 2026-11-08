// Command ygg-extract-rust emits mechanically scanned Rust declarations,
// use paths and module declarations through the Yggdrasil extractor protocol.
package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path"
	"strings"
	"unicode"
	"unicode/utf8"
)

const (
	schema   = "ygg.extractor/v1"
	maxFacts = 256
	maxText  = 64*1024 - 1
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

// token is an identifier, a single punctuation character, or a string
// literal (text keeps the decoded body of plain strings, quoted with ").
type token struct {
	text  string
	line  int
	str   bool
	ident bool
}

// scan tokenizes Rust source, dropping comments (nested block comments
// included), char and byte literals, lifetimes and numbers.
func scan(content string) []token {
	var result []token
	line := 1
	runes := []rune(content)
	at := func(index int) rune {
		if index < len(runes) {
			return runes[index]
		}
		return 0
	}
	for index := 0; index < len(runes); {
		current := runes[index]
		switch {
		case current == '\n':
			line++
			index++
		case unicode.IsSpace(current):
			index++
		case current == '/' && at(index+1) == '/':
			for index < len(runes) && runes[index] != '\n' {
				index++
			}
		case current == '/' && at(index+1) == '*':
			depth := 0
			for index < len(runes) {
				if runes[index] == '/' && at(index+1) == '*' {
					depth++
					index += 2
					continue
				}
				if runes[index] == '*' && at(index+1) == '/' {
					depth--
					index += 2
					if depth == 0 {
						break
					}
					continue
				}
				if runes[index] == '\n' {
					line++
				}
				index++
			}
		case current == '"' || ((current == 'b' || current == 'c') && at(index+1) == '"'):
			if current != '"' {
				index++
			}
			start := line
			index++
			var body strings.Builder
			for index < len(runes) && runes[index] != '"' {
				if runes[index] == '\\' {
					index++
					if at(index) == '\n' {
						line++
					}
					index++
					continue
				}
				if runes[index] == '\n' {
					line++
				}
				body.WriteRune(runes[index])
				index++
			}
			index++
			result = append(result, token{text: body.String(), line: start, str: true})
		case rawStringStart(runes, index) > 0:
			index += rawStringStart(runes, index) - 1
			hashes := 0
			for at(index) == '#' {
				hashes++
				index++
			}
			index++ // opening quote
			start := line
			closing := "\"" + strings.Repeat("#", hashes)
			var body strings.Builder
			for index < len(runes) && !strings.HasPrefix(string(runes[index:min(len(runes), index+len(closing))]), closing) {
				if runes[index] == '\n' {
					line++
				}
				body.WriteRune(runes[index])
				index++
			}
			index += len(closing)
			result = append(result, token{text: body.String(), line: start, str: true})
		case current == '\'' || (current == 'b' && at(index+1) == '\''):
			if current == 'b' {
				index++
			}
			index++
			if at(index) == '\\' {
				for index < len(runes) && runes[index] != '\'' && runes[index] != '\n' {
					if runes[index] == '\\' {
						index++
					}
					index++
				}
				index++
			} else if at(index+1) == '\'' {
				index += 2
			} else {
				for index < len(runes) && isIdentRune(runes[index], 1) {
					index++
				}
			}
		case isIdentRune(current, 0):
			start := index
			for index < len(runes) && isIdentRune(runes[index], 1) {
				index++
			}
			text := string(runes[start:index])
			if text == "r" && at(index) == '#' && isIdentRune(at(index+1), 0) {
				start = index + 1
				for index = start; index < len(runes) && isIdentRune(runes[index], 1); index++ {
				}
				text = string(runes[start:index])
			}
			result = append(result, token{text: text, line: line, ident: true})
		case unicode.IsDigit(current):
			for index < len(runes) && (isIdentRune(runes[index], 1) || runes[index] == '.' && unicode.IsDigit(at(index+1))) {
				index++
			}
		default:
			result = append(result, token{text: string(current), line: line})
			index++
		}
	}
	return result
}

// rawStringStart returns the prefix length ("r", "br" or "cr") when a raw
// string literal starts at index, else 0.
func rawStringStart(runes []rune, index int) int {
	if index > 0 && isIdentRune(runes[index-1], 1) {
		return 0
	}
	prefix := 0
	switch {
	case runes[index] == 'r':
		prefix = 1
	case (runes[index] == 'b' || runes[index] == 'c') && index+1 < len(runes) && runes[index+1] == 'r':
		prefix = 2
	default:
		return 0
	}
	next := index + prefix
	for next < len(runes) && runes[next] == '#' {
		next++
	}
	if next < len(runes) && runes[next] == '"' {
		return prefix + 1
	}
	return 0
}

func isIdentRune(current rune, index int) bool {
	return current == '_' || unicode.IsLetter(current) || (index > 0 && unicode.IsDigit(current))
}

// frame is one open brace. kind is "mod", "impl", "trait" or "other"; owner
// names the impl or trait type for methods; fact is the record whose span the
// closing brace ends, or -1.
type frame struct {
	kind   string
	owner  string
	name   string
	fact   int
	parens int
}

type extractor struct {
	tokens  []token
	lines   []string
	facts   []record
	stack   []frame
	pending int
	kind    string
	owner   string
	name    string
	parens  int
	pathTo  string
}

func extract(file, content string) []record {
	state := &extractor{tokens: scan(content), lines: strings.Split(content, "\n"), pending: -1}
	state.walk()
	records := state.facts
	if len(records) > 0 {
		records = append([]record{navigationRecord(file, records)}, records...)
	}
	return records
}

// scope reports "mod" when every open brace is a module, "impl" or "trait"
// when the innermost brace is one directly inside module scope, else "".
func (s *extractor) scope() (string, string) {
	for index, value := range s.stack {
		if value.kind == "mod" {
			continue
		}
		if index == len(s.stack)-1 && (value.kind == "impl" || value.kind == "trait") {
			return value.kind, value.owner
		}
		return "", ""
	}
	return "mod", ""
}

func (s *extractor) modulePath() []string {
	var names []string
	for _, value := range s.stack {
		if value.kind == "mod" {
			names = append(names, value.name)
		}
	}
	return names
}

func (s *extractor) walk() {
	tokens := s.tokens
	for index := 0; index < len(tokens); index++ {
		current := tokens[index]
		if current.str {
			continue
		}
		switch current.text {
		case "(", "[":
			s.parens++
			continue
		case ")", "]":
			if s.parens > 0 {
				s.parens--
			}
			continue
		case "{":
			value := frame{kind: "other", fact: -1, parens: s.parens}
			if s.pending >= 0 || s.kind != "" {
				value = frame{kind: s.kind, owner: s.owner, name: s.name, fact: s.pending, parens: s.parens}
				if value.kind == "" {
					value.kind = "other"
				}
				s.clearPending()
			}
			s.stack = append(s.stack, value)
			continue
		case "}":
			if len(s.stack) > 0 {
				value := s.stack[len(s.stack)-1]
				s.stack = s.stack[:len(s.stack)-1]
				if value.fact >= 0 && value.fact < len(s.facts) {
					s.facts[value.fact].EndLine = current.line
				}
				s.parens = value.parens
			}
			continue
		case ";":
			if s.parens == 0 && (s.pending >= 0 || s.kind != "") {
				if s.pending >= 0 {
					s.facts[s.pending].EndLine = current.line
				}
				s.clearPending()
			}
			continue
		case "#":
			index = s.attribute(index)
			continue
		}
		if !current.ident || s.parens != 0 || s.pending >= 0 || s.kind != "" || len(s.facts) >= maxFacts {
			continue
		}
		scope, owner := s.scope()
		if scope == "" {
			continue
		}
		index = s.item(index, scope, owner)
	}
}

// attribute skips #[...] and #![...], remembering a #[path = "..."] value for
// the next module declaration.
func (s *extractor) attribute(start int) int {
	tokens := s.tokens
	index := start + 1
	if index < len(tokens) && tokens[index].text == "!" && !tokens[index].str {
		index++
	}
	if index >= len(tokens) || tokens[index].text != "[" || tokens[index].str {
		return start
	}
	depth := 0
	for ; index < len(tokens); index++ {
		value := tokens[index]
		if value.str {
			continue
		}
		if value.text == "[" {
			depth++
		}
		if value.text == "]" {
			depth--
			if depth == 0 {
				break
			}
		}
		if depth == 1 && value.text == "path" && index+2 < len(tokens) &&
			tokens[index+1].text == "=" && tokens[index+2].str {
			s.pathTo = tokens[index+2].text
		}
	}
	return index
}

var qualifiers = map[string]bool{
	"pub": true, "unsafe": true, "async": true, "default": true, "extern": true, "auto": true,
}

func (s *extractor) item(start int, scope, owner string) int {
	tokens := s.tokens
	index := start
	for index < len(tokens) && (qualifiers[tokens[index].text] || tokens[index].str) && !(tokens[index].str && index == start) {
		if tokens[index].text == "pub" && index+1 < len(tokens) && tokens[index+1].text == "(" {
			index = skipGroup(tokens, index+1)
		}
		if tokens[index].text == "extern" && index+1 < len(tokens) && tokens[index+1].text == "crate" {
			break
		}
		index++
	}
	if index >= len(tokens) || !tokens[index].ident {
		return start
	}
	keyword := tokens[index].text
	if keyword == "const" && index+1 < len(tokens) && (tokens[index+1].text == "fn" ||
		tokens[index+1].text == "unsafe" || tokens[index+1].text == "async" || tokens[index+1].text == "extern") {
		index++
		for index < len(tokens) && keyword != "fn" && tokens[index].text != "fn" {
			index++
		}
		keyword = "fn"
	}
	line := tokens[start].line
	switch keyword {
	case "fn", "type", "const", "static":
		nameIndex := index + 1
		if keyword == "static" && nameIndex < len(tokens) && tokens[nameIndex].text == "mut" {
			nameIndex++
		}
		if nameIndex >= len(tokens) || !tokens[nameIndex].ident || tokens[nameIndex].text == "_" {
			s.kind = "other"
			return nameIndex
		}
		name := tokens[nameIndex].text
		kind := "rust-" + keyword
		title := name
		if scope != "mod" {
			kind = "rust-method"
			if keyword != "fn" {
				kind = "rust-assoc-" + keyword
			}
			title = owner + "::" + name
		}
		s.open(kind, title, name, line, "other", "", "")
		return nameIndex
	case "struct", "enum", "trait", "union":
		if keyword == "union" && (index+1 >= len(tokens) || !tokens[index+1].ident) {
			return start
		}
		if scope != "mod" || index+1 >= len(tokens) || !tokens[index+1].ident {
			return start
		}
		name := tokens[index+1].text
		body := "other"
		if keyword == "trait" {
			body = "trait"
		}
		s.open("rust-"+keyword, name, name, line, body, name, "")
		return index + 1
	case "mod":
		if scope != "mod" || index+1 >= len(tokens) || !tokens[index+1].ident {
			return start
		}
		name := tokens[index+1].text
		if index+2 < len(tokens) && tokens[index+2].text == ";" {
			target := strings.Join(append(s.modulePath(), name), "/")
			if s.pathTo != "" {
				target = "./" + strings.TrimPrefix(path.Clean(strings.Join(append(s.modulePath(), s.pathTo), "/")), "./")
			}
			s.pathTo = ""
			s.addFact("rust-import", target, line, line, s.lineText(line)+"\nmodule "+identifierWords(name))
			return index + 2
		}
		s.pathTo = ""
		s.open("rust-mod", name, name, line, "mod", "", name)
		return index + 1
	case "impl":
		if scope != "mod" {
			return start
		}
		title, typeName, end := implTitle(tokens, index+1)
		if title == "" {
			return start
		}
		s.open("rust-impl", title, typeName, line, "impl", typeName, "")
		return end - 1
	case "macro_rules":
		if scope != "mod" || index+2 >= len(tokens) || tokens[index+1].text != "!" || !tokens[index+2].ident {
			return start
		}
		name := tokens[index+2].text
		s.open("rust-macro", name, name, line, "other", "", "")
		return index + 2
	case "use":
		if scope != "mod" {
			return start
		}
		var text strings.Builder
		end := index + 1
		for ; end < len(tokens) && !(tokens[end].text == ";" && !tokens[end].str); end++ {
			if tokens[end].ident && end > index+1 && tokens[end-1].ident {
				text.WriteByte(' ')
			}
			text.WriteString(tokens[end].text)
		}
		if text.Len() == 0 {
			return start
		}
		endLine := line
		if end < len(tokens) {
			endLine = tokens[end].line
		}
		value := text.String()
		s.addFact("rust-use", value, line, endLine, s.lineText(line)+"\n"+useWords(value))
		return end
	case "extern":
		if index+2 < len(tokens) && tokens[index+1].text == "crate" && tokens[index+2].ident {
			name := tokens[index+2].text
			s.addFact("rust-use", name, line, line, s.lineText(line)+"\n"+identifierWords(name))
			return index + 2
		}
	}
	return start
}

func (s *extractor) open(kind, title, name string, line int, body, owner, module string) {
	if len(s.facts) >= maxFacts {
		s.kind = body
		return
	}
	s.facts = append(s.facts, makeRecord(kind, title, line, line, s.lineText(line)+"\n"+identifierWords(name)))
	s.pending = len(s.facts) - 1
	s.kind = body
	s.owner = owner
	s.name = module
}

func (s *extractor) addFact(kind, title string, line, end int, text string) {
	if len(s.facts) < maxFacts {
		s.facts = append(s.facts, makeRecord(kind, title, line, end, text))
	}
}

func (s *extractor) clearPending() {
	s.pending, s.kind, s.owner, s.name = -1, "", "", ""
}

func (s *extractor) lineText(line int) string {
	if line < 1 || line > len(s.lines) {
		return ""
	}
	return strings.TrimSpace(s.lines[line-1])
}

func skipGroup(tokens []token, start int) int {
	depth := 0
	for index := start; index < len(tokens); index++ {
		if tokens[index].str {
			continue
		}
		switch tokens[index].text {
		case "(":
			depth++
		case ")":
			depth--
			if depth == 0 {
				return index
			}
		}
	}
	return len(tokens) - 1
}

// implTitle reads `impl<..> Trait for Type<..> where .. {` and returns
// "Trait for Type" (or "Type"), the type's last path segment, and the index
// of the opening brace.
func implTitle(tokens []token, start int) (string, string, int) {
	angle := 0
	var first, second string
	var afterFor, inWhere bool
	index := start
	for ; index < len(tokens); index++ {
		value := tokens[index]
		if value.str {
			continue
		}
		if angle == 0 && (value.text == "{" || value.text == ";") {
			break
		}
		switch value.text {
		case "<":
			angle++
			continue
		case ">":
			if index > 0 && tokens[index-1].text == "-" {
				continue
			}
			if angle > 0 {
				angle--
			}
			continue
		}
		if angle != 0 || !value.ident || inWhere {
			continue
		}
		if value.text == "where" {
			inWhere = true
			continue
		}
		if value.text == "for" {
			afterFor = true
			continue
		}
		if value.text == "dyn" || value.text == "unsafe" || value.text == "const" {
			continue
		}
		if afterFor {
			if second == "" || segmentContinues(tokens, index) {
				second = value.text
			}
		} else if first == "" || segmentContinues(tokens, index) {
			first = value.text
		}
	}
	if index >= len(tokens) || tokens[index].text != "{" {
		return "", "", index
	}
	if first == "" {
		return "", "", index
	}
	if second != "" {
		return first + " for " + second, second, index
	}
	return first, first, index
}

// segmentContinues reports whether the identifier at index follows `::`,
// so a path's last segment replaces its earlier ones.
func segmentContinues(tokens []token, index int) bool {
	return index >= 2 && tokens[index-1].text == ":" && tokens[index-2].text == ":"
}

func makeRecord(kind, title string, line, end int, text string) record {
	return record{
		ID:        kind + ":" + fmt.Sprint(line) + ":" + title,
		StartLine: line, EndLine: end, Kind: kind, Title: title, Text: truncate(text),
		Metadata: map[string]any{"semantic": false},
	}
}

func navigationRecord(file string, records []record) record {
	var text strings.Builder
	fmt.Fprintf(&text, "file %s\nkind rust\n", file)
	for _, value := range records {
		fmt.Fprintf(&text, "%s %s\n", value.Kind, value.Title)
	}
	return record{
		ID: "rust-navigation:1", StartLine: 1, EndLine: 1,
		Kind: "rust-navigation", Title: file,
		Text:     truncate(strings.TrimSpace(text.String())),
		Metadata: map[string]any{"facts": len(records), "lexical": false},
	}
}

func useWords(value string) string {
	var words []string
	for _, part := range strings.FieldsFunc(value, func(current rune) bool {
		return !isIdentRune(current, 1)
	}) {
		words = append(words, identifierWords(part))
	}
	return strings.Join(words, " ")
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
