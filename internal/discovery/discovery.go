// Package discovery finds mechanically indexable repository files.
package discovery

import (
	"bytes"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"os/exec"
	"path"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"unicode/utf8"
)

type Candidate struct {
	Path    string
	Size    int64
	MTimeNS int64
}

type File struct {
	Candidate
	Kind    string
	Content string
}

type Skipped struct {
	Path   string
	Reason string
}

func Candidates(root string, ignoreGlobs []string) ([]Candidate, error) {
	paths, err := gitPaths(root)
	if err != nil {
		paths, err = walkPaths(root)
	}
	if err != nil {
		return nil, err
	}
	result := make([]Candidate, 0, len(paths))
	for _, rel := range paths {
		if ignored(rel, ignoreGlobs) {
			continue
		}
		info, err := os.Stat(filepath.Join(root, filepath.FromSlash(rel)))
		if err != nil || !info.Mode().IsRegular() {
			continue
		}
		result = append(result, Candidate{
			Path:    rel,
			Size:    info.Size(),
			MTimeNS: info.ModTime().UnixNano(),
		})
	}
	sort.Slice(result, func(i, j int) bool { return result[i].Path < result[j].Path })
	return result, nil
}

func Read(root string, candidate Candidate, maxBytes int64) (File, *Skipped, error) {
	if candidate.Size > maxBytes {
		return File{}, &Skipped{Path: candidate.Path, Reason: "file-too-large"}, nil
	}
	data, err := os.ReadFile(filepath.Join(root, filepath.FromSlash(candidate.Path)))
	if err != nil {
		return File{}, nil, fmt.Errorf("read %s: %w", candidate.Path, err)
	}
	if bytes.IndexByte(data, 0) >= 0 || !utf8.Valid(data) {
		return File{}, &Skipped{Path: candidate.Path, Reason: "non-text"}, nil
	}
	ext := strings.TrimPrefix(strings.ToLower(path.Ext(candidate.Path)), ".")
	if ext == "" {
		ext = "text"
	}
	return File{Candidate: candidate, Kind: ext, Content: string(data)}, nil, nil
}

func Match(pattern, value string) bool {
	var expression strings.Builder
	expression.WriteByte('^')
	for i := 0; i < len(pattern); {
		switch pattern[i] {
		case '*':
			if i+1 < len(pattern) && pattern[i+1] == '*' {
				expression.WriteString(".*")
				i += 2
			} else {
				expression.WriteString("[^/]*")
				i++
			}
		case '?':
			expression.WriteString("[^/]")
			i++
		default:
			expression.WriteString(regexp.QuoteMeta(pattern[i : i+1]))
			i++
		}
	}
	expression.WriteByte('$')
	ok, err := regexp.MatchString(expression.String(), filepath.ToSlash(value))
	return err == nil && ok
}

func ignored(rel string, patterns []string) bool {
	if rel == ".git" || strings.HasPrefix(rel, ".git/") {
		return true
	}
	for _, pattern := range patterns {
		if Match(filepath.ToSlash(pattern), rel) {
			return true
		}
	}
	return false
}

func gitPaths(root string) ([]string, error) {
	cmd := exec.Command("git", "-C", root, "ls-files", "-co", "--exclude-standard", "-z")
	output, err := cmd.Output()
	if err != nil {
		return nil, err
	}
	raw := bytes.Split(output, []byte{0})
	paths := make([]string, 0, len(raw))
	for _, item := range raw {
		if len(item) > 0 {
			paths = append(paths, filepath.ToSlash(string(item)))
		}
	}
	return paths, nil
}

func walkPaths(root string) ([]string, error) {
	var paths []string
	err := filepath.WalkDir(root, func(value string, entry fs.DirEntry, err error) error {
		if err != nil {
			if errors.Is(err, fs.ErrPermission) {
				return nil
			}
			return err
		}
		rel, err := filepath.Rel(root, value)
		if err != nil {
			return err
		}
		rel = filepath.ToSlash(rel)
		if entry.IsDir() {
			if rel == ".git" || strings.HasPrefix(rel, ".git/") {
				return filepath.SkipDir
			}
			return nil
		}
		if entry.Type().IsRegular() {
			paths = append(paths, rel)
		}
		return nil
	})
	return paths, err
}
