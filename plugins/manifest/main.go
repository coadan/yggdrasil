// Command ygg-extract-manifest emits searchable package and workspace facts
// from explicitly supported manifest grammars.
package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
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
	Title     string         `json:"title,omitempty"`
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
	switch filepath.Base(path) {
	case "package.json":
		return extractPackageJSON(content)
	case "go.mod":
		return extractGoMod(content), nil
	case "Cargo.toml":
		return extractCargo(content), nil
	case "pyproject.toml":
		return extractPyproject(content), nil
	default:
		return nil, []diagnostic{{Message: "unsupported manifest grammar"}}
	}
}

func extractPackageJSON(content string) ([]record, []diagnostic) {
	var manifest struct {
		Name                 string            `json:"name"`
		Version              string            `json:"version"`
		PackageManager       string            `json:"packageManager"`
		Dependencies         map[string]string `json:"dependencies"`
		DevDependencies      map[string]string `json:"devDependencies"`
		PeerDependencies     map[string]string `json:"peerDependencies"`
		OptionalDependencies map[string]string `json:"optionalDependencies"`
		Scripts              map[string]string `json:"scripts"`
		Workspaces           json.RawMessage   `json:"workspaces"`
	}
	if err := json.Unmarshal([]byte(content), &manifest); err != nil {
		return nil, []diagnostic{{Message: err.Error()}}
	}
	var records []record
	if manifest.Name != "" {
		records = append(records, fact(content, "npm-package", manifest.Name, manifest.Name,
			map[string]any{"ecosystem": "npm", "version": manifest.Version}))
	}
	if manifest.PackageManager != "" {
		records = append(records, fact(content, "package-manager", manifest.PackageManager, manifest.PackageManager, nil))
	}
	groups := []struct {
		scope string
		items map[string]string
	}{
		{"runtime", manifest.Dependencies},
		{"development", manifest.DevDependencies},
		{"peer", manifest.PeerDependencies},
		{"optional", manifest.OptionalDependencies},
	}
	for _, group := range groups {
		for _, name := range sortedKeys(group.items) {
			version := group.items[name]
			records = append(records, dependency(content, "npm", group.scope, name, version))
		}
	}
	for _, name := range sortedKeys(manifest.Scripts) {
		command := manifest.Scripts[name]
		records = append(records, fact(content, "package-script", name, name+" "+command,
			map[string]any{"command": command}))
	}
	for _, workspace := range packageWorkspaces(manifest.Workspaces) {
		records = append(records, fact(content, "workspace-member", workspace, workspace, nil))
	}
	return limit(records), nil
}

func packageWorkspaces(raw json.RawMessage) []string {
	var direct []string
	if json.Unmarshal(raw, &direct) == nil {
		return direct
	}
	var nested struct {
		Packages []string `json:"packages"`
	}
	_ = json.Unmarshal(raw, &nested)
	return nested.Packages
}

func extractGoMod(content string) []record {
	var records []record
	inRequire := false
	for _, value := range sourceLines(content) {
		line := strings.TrimSpace(strings.SplitN(value.text, "//", 2)[0])
		if line == "" {
			continue
		}
		fields := strings.Fields(line)
		switch {
		case fields[0] == "module" && len(fields) >= 2:
			records = append(records, atLine(value.number, "go-module", fields[1], line,
				map[string]any{"ecosystem": "go"}))
		case fields[0] == "go" && len(fields) >= 2:
			records = append(records, atLine(value.number, "go-version", fields[1], line, nil))
		case fields[0] == "toolchain" && len(fields) >= 2:
			records = append(records, atLine(value.number, "go-toolchain", fields[1], line, nil))
		case fields[0] == "require" && len(fields) == 2 && fields[1] == "(":
			inRequire = true
		case inRequire && line == ")":
			inRequire = false
		case inRequire && len(fields) >= 2:
			records = append(records, dependencyAt(value.number, "go", "runtime", fields[0], fields[1], line))
		case fields[0] == "require" && len(fields) >= 3:
			records = append(records, dependencyAt(value.number, "go", "runtime", fields[1], fields[2], line))
		case fields[0] == "replace" && len(fields) >= 4:
			title := fields[1] + "=>" + strings.Join(fields[3:], " ")
			records = append(records, atLine(value.number, "go-replace", title, line,
				map[string]any{"module": fields[1], "replacement": strings.Join(fields[3:], " ")}))
		case fields[0] == "exclude" && len(fields) >= 3:
			title := fields[1] + "@" + fields[2]
			records = append(records, atLine(value.number, "go-exclude", title, line,
				map[string]any{"module": fields[1], "version": fields[2]}))
		}
	}
	return limit(records)
}

var (
	tomlSection = regexp.MustCompile(`^\s*\[([^\]]+)]`)
	tomlKV      = regexp.MustCompile(`^\s*([A-Za-z0-9_.-]+)\s*=\s*(.+?)\s*(?:#.*)?$`)
	quoted      = regexp.MustCompile(`["']([^"']+)["']`)
	versionKey  = regexp.MustCompile(`version\s*=\s*["']([^"']+)["']`)
)

func extractCargo(content string) []record {
	var records []record
	section := ""
	for _, value := range sourceLines(content) {
		if match := tomlSection.FindStringSubmatch(value.text); match != nil {
			section = match[1]
			continue
		}
		match := tomlKV.FindStringSubmatch(value.text)
		if match == nil {
			continue
		}
		key, raw := match[1], match[2]
		switch {
		case section == "package" && key == "name":
			name := unquote(raw)
			records = append(records, atLine(value.number, "cargo-package", name, value.text,
				map[string]any{"ecosystem": "cargo"}))
		case cargoDependencyScope(section) != "":
			version := unquote(raw)
			if submatch := versionKey.FindStringSubmatch(raw); submatch != nil {
				version = submatch[1]
			}
			records = append(records, dependencyAt(value.number, "cargo", cargoDependencyScope(section), key, version, value.text))
		case section == "workspace" && key == "members":
			for _, member := range quotedValues(raw) {
				records = append(records, atLine(value.number, "workspace-member", member, member, nil))
			}
		case section == "features":
			records = append(records, atLine(value.number, "cargo-feature", key, value.text, nil))
		}
	}
	return limit(records)
}

func cargoDependencyScope(section string) string {
	switch section {
	case "dependencies", "workspace.dependencies":
		return "runtime"
	case "dev-dependencies":
		return "development"
	case "build-dependencies":
		return "build"
	default:
		return ""
	}
}

func extractPyproject(content string) []record {
	var records []record
	section := ""
	for _, value := range sourceLines(content) {
		if match := tomlSection.FindStringSubmatch(value.text); match != nil {
			section = match[1]
			continue
		}
		match := tomlKV.FindStringSubmatch(value.text)
		if match == nil {
			continue
		}
		key, raw := match[1], match[2]
		switch {
		case section == "project" && key == "name":
			name := unquote(raw)
			records = append(records, atLine(value.number, "pypi-project", name, value.text,
				map[string]any{"ecosystem": "pypi"}))
		case section == "project" && key == "dependencies":
			for _, requirement := range quotedValues(raw) {
				name, version := pythonRequirement(requirement)
				records = append(records, dependencyAt(value.number, "pypi", "runtime", name, version, requirement))
			}
		case section == "project.optional-dependencies":
			for _, requirement := range quotedValues(raw) {
				name, version := pythonRequirement(requirement)
				records = append(records, dependencyAt(value.number, "pypi", key, name, version, requirement))
			}
		case strings.HasPrefix(section, "tool.poetry.dependencies"):
			if key != "python" {
				records = append(records, dependencyAt(value.number, "pypi", "runtime", key, unquote(raw), value.text))
			}
		case strings.HasPrefix(section, "tool.poetry.group.") && strings.HasSuffix(section, ".dependencies"):
			parts := strings.Split(section, ".")
			records = append(records, dependencyAt(value.number, "pypi", parts[3], key, unquote(raw), value.text))
		}
	}
	return limit(records)
}

func pythonRequirement(value string) (string, string) {
	name := value
	if index := strings.IndexAny(name, "<>=!~;[ "); index >= 0 {
		name = name[:index]
	}
	return name, strings.TrimSpace(strings.TrimPrefix(value, name))
}

func dependency(content, ecosystem, scope, name, version string) record {
	line := findLine(content, name)
	return dependencyAt(line, ecosystem, scope, name, version, lineText(content, line))
}

func dependencyAt(line int, ecosystem, scope, name, version, text string) record {
	return atLine(line, ecosystem+"-dependency", name, text, map[string]any{
		"ecosystem": ecosystem, "packageName": name, "version": version, "scope": scope,
	})
}

func fact(content, kind, title, text string, metadata map[string]any) record {
	return atLine(findLine(content, title), kind, title, text, metadata)
}

func atLine(line int, kind, title, text string, metadata map[string]any) record {
	if line < 1 {
		line = 1
	}
	return record{
		ID: fmt.Sprintf("%s:%d:%s", kind, line, title), StartLine: line, EndLine: line,
		Kind: kind, Title: title, Text: strings.TrimSpace(text), Metadata: metadata,
	}
}

type sourceLine struct {
	number int
	text   string
}

func sourceLines(content string) []sourceLine {
	lines := strings.Split(content, "\n")
	result := make([]sourceLine, len(lines))
	for index, text := range lines {
		result[index] = sourceLine{number: index + 1, text: text}
	}
	return result
}

func findLine(content, value string) int {
	for _, line := range sourceLines(content) {
		if strings.Contains(line.text, value) {
			return line.number
		}
	}
	return 1
}

func lineText(content string, number int) string {
	lines := strings.Split(content, "\n")
	if number < 1 || number > len(lines) {
		return ""
	}
	return strings.TrimSpace(lines[number-1])
}

func quotedValues(value string) []string {
	matches := quoted.FindAllStringSubmatch(value, -1)
	result := make([]string, 0, len(matches))
	for _, match := range matches {
		result = append(result, match[1])
	}
	return result
}

func unquote(value string) string {
	value = strings.TrimSpace(value)
	if result, err := strconv.Unquote(value); err == nil {
		return result
	}
	return strings.Trim(value, `"'`)
}

func sortedKeys(values map[string]string) []string {
	keys := make([]string, 0, len(values))
	for key := range values {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	return keys
}

func limit(records []record) []record {
	if len(records) > maxRecords {
		return records[:maxRecords]
	}
	return records
}
