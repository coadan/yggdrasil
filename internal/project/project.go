// Package project resolves repository roots and their central state paths.
package project

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

type Paths struct {
	Root         string
	Scope        string
	ID           string
	FamilyID     string
	Head         string
	StateDir     string
	Database     string
	IndexLock    string
	FamilyMarker string
}

type IndexSeed struct {
	Root        string
	Database    string
	Head        string
	UpdatedAtMS int64
}

const indexFamilySchema = "ygg.index-family/v1"

type indexFamily struct {
	Schema   string `json:"schema"`
	FamilyID string `json:"familyId"`
	Root     string `json:"root"`
	Head     string `json:"head,omitempty"`
	Updated  int64  `json:"updatedAtMs"`
}

func ResolveRoot(explicit string) (string, error) {
	root, _, err := resolveRootScope(explicit)
	return root, err
}

func gitRoot(root string) (string, error) {
	output, err := exec.Command("git", "-C", root, "rev-parse", "--show-toplevel").Output()
	if err != nil {
		return root, nil
	}
	return canonicalDir(strings.TrimSpace(string(output)))
}

func Resolve(explicit string) (Paths, error) {
	root, scope, err := resolveRootScope(explicit)
	if err != nil {
		return Paths{}, err
	}
	storageRoot, err := storageRoot()
	if err != nil {
		return Paths{}, err
	}
	familyRoot := root
	if output, gitErr := exec.Command(
		"git", "-C", root, "rev-parse", "--path-format=absolute", "--git-common-dir",
	).Output(); gitErr == nil {
		if common, canonicalErr := canonicalDir(strings.TrimSpace(string(output))); canonicalErr == nil {
			familyRoot = common
		}
	}
	head := ""
	if output, gitErr := exec.Command("git", "-C", root, "rev-parse", "HEAD").Output(); gitErr == nil {
		head = strings.TrimSpace(string(output))
	}
	paths := pathsForRoot(root, storageRoot, hashID(familyRoot), head)
	paths.Scope = scope
	return paths, nil
}

func resolveRootScope(explicit string) (string, string, error) {
	if explicit == "" {
		if output, err := exec.Command("git", "rev-parse", "--show-toplevel").Output(); err == nil {
			root, canonicalErr := canonicalDir(strings.TrimSpace(string(output)))
			return root, "", canonicalErr
		}
		root, err := canonicalDir(".")
		return root, "", err
	}
	requested, info, err := canonicalPath(explicit)
	if err != nil {
		return "", "", err
	}
	probe := requested
	if !info.IsDir() {
		probe = filepath.Dir(requested)
	}
	root, err := gitRoot(probe)
	if err != nil {
		return "", "", err
	}
	if requested == root {
		return root, "", nil
	}
	scope, err := filepath.Rel(root, requested)
	if err != nil {
		return "", "", fmt.Errorf("resolve repository scope: %w", err)
	}
	if scope == ".." || strings.HasPrefix(scope, ".."+string(filepath.Separator)) {
		return "", "", errors.New("repository scope is outside the resolved root")
	}
	scope = filepath.ToSlash(scope)
	if info.IsDir() {
		scope += "/"
	}
	return root, scope, nil
}

func RecordIndexFamily(paths Paths) error {
	if err := os.MkdirAll(paths.StateDir, 0o755); err != nil {
		return fmt.Errorf("create index state directory: %w", err)
	}
	data, err := json.Marshal(indexFamily{
		Schema: indexFamilySchema, FamilyID: paths.FamilyID, Root: paths.Root,
		Head: paths.Head, Updated: time.Now().UnixMilli(),
	})
	if err != nil {
		return err
	}
	temporary, err := os.CreateTemp(paths.StateDir, "family-*.json")
	if err != nil {
		return fmt.Errorf("create index family marker: %w", err)
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)
	if err := temporary.Chmod(0o600); err != nil {
		temporary.Close()
		return err
	}
	if _, err := temporary.Write(append(data, '\n')); err != nil {
		temporary.Close()
		return err
	}
	if err := temporary.Close(); err != nil {
		return err
	}
	if err := os.Rename(temporaryPath, paths.FamilyMarker); err != nil {
		return fmt.Errorf("publish index family marker: %w", err)
	}
	return nil
}

// SiblingIndexes returns existing indexes for other linked worktrees, preferring
// a worktree at the same commit so the seed requires the least reconciliation.
func SiblingIndexes(ctx context.Context, paths Paths) ([]IndexSeed, error) {
	output, err := exec.CommandContext(
		ctx, "git", "-C", paths.Root, "worktree", "list", "--porcelain", "-z",
	).Output()
	type worktree struct {
		root string
		head string
	}
	var worktrees []worktree
	var current worktree
	var item worktree
	if err == nil {
		for _, field := range strings.Split(string(output), "\x00") {
			switch {
			case strings.HasPrefix(field, "worktree "):
				item.root = strings.TrimPrefix(field, "worktree ")
			case strings.HasPrefix(field, "HEAD "):
				item.head = strings.TrimPrefix(field, "HEAD ")
			case field == "":
				if item.root != "" {
					root, canonicalErr := canonicalDir(item.root)
					if canonicalErr == nil {
						item.root = root
						worktrees = append(worktrees, item)
						if root == paths.Root {
							current = item
						}
					}
					item = worktree{}
				}
			}
		}
	}
	storage := filepath.Dir(filepath.Dir(paths.StateDir))
	var sameHead, other []IndexSeed
	seen := map[string]bool{paths.Database: true}
	for _, candidate := range worktrees {
		if candidate.root == paths.Root {
			continue
		}
		candidatePaths := pathsForRoot(candidate.root, storage, paths.FamilyID, candidate.head)
		info, statErr := os.Stat(candidatePaths.Database)
		if statErr != nil || info.IsDir() || info.Size() == 0 {
			continue
		}
		seen[candidatePaths.Database] = true
		seed := IndexSeed{
			Root: candidate.root, Database: candidatePaths.Database, Head: candidate.head,
			UpdatedAtMS: info.ModTime().UnixMilli(),
		}
		if current.head != "" && candidate.head == current.head {
			sameHead = append(sameHead, seed)
		} else {
			other = append(other, seed)
		}
	}
	entries, readErr := os.ReadDir(filepath.Join(storage, "indexes"))
	if readErr != nil && !errors.Is(readErr, os.ErrNotExist) {
		return nil, fmt.Errorf("read retained indexes: %w", readErr)
	}
	var retainedSameHead, retainedOther []IndexSeed
	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}
		stateDir := filepath.Join(storage, "indexes", entry.Name())
		database := filepath.Join(stateDir, "search.sqlite3")
		if seen[database] {
			continue
		}
		data, readErr := os.ReadFile(filepath.Join(stateDir, "family.json"))
		if readErr != nil {
			continue
		}
		var family indexFamily
		decoder := json.NewDecoder(bytes.NewReader(data))
		decoder.DisallowUnknownFields()
		if decoder.Decode(&family) != nil ||
			family.Schema != indexFamilySchema || family.FamilyID != paths.FamilyID {
			continue
		}
		info, statErr := os.Stat(database)
		if statErr != nil || info.IsDir() || info.Size() == 0 {
			continue
		}
		seed := IndexSeed{
			Root: family.Root, Database: database, Head: family.Head, UpdatedAtMS: family.Updated,
		}
		if paths.Head != "" && family.Head == paths.Head {
			retainedSameHead = append(retainedSameHead, seed)
		} else {
			retainedOther = append(retainedOther, seed)
		}
	}
	sortSeedsNewestFirst(sameHead)
	sortSeedsNewestFirst(retainedSameHead)
	sortSeedsNewestFirst(other)
	sortSeedsNewestFirst(retainedOther)
	result := append(sameHead, retainedSameHead...)
	result = append(result, other...)
	return append(result, retainedOther...), nil
}

func sortSeedsNewestFirst(seeds []IndexSeed) {
	sort.SliceStable(seeds, func(i, j int) bool {
		if seeds[i].UpdatedAtMS != seeds[j].UpdatedAtMS {
			return seeds[i].UpdatedAtMS > seeds[j].UpdatedAtMS
		}
		return seeds[i].Database < seeds[j].Database
	})
}

func pathsForRoot(root, storageRoot, familyID, head string) Paths {
	id := hashID(root)
	stateDir := filepath.Join(storageRoot, "indexes", id)
	return Paths{
		Root:         root,
		ID:           id,
		FamilyID:     familyID,
		Head:         head,
		StateDir:     stateDir,
		Database:     filepath.Join(stateDir, "search.sqlite3"),
		IndexLock:    filepath.Join(stateDir, "index.lock"),
		FamilyMarker: filepath.Join(stateDir, "family.json"),
	}
}

func hashID(value string) string {
	sum := sha256.Sum256([]byte(value))
	return hex.EncodeToString(sum[:])[:24]
}

func canonicalDir(path string) (string, error) {
	value, info, err := canonicalPath(path)
	if err != nil {
		return "", err
	}
	if !info.IsDir() {
		return "", errors.New("repository root is not a directory")
	}
	return value, nil
}

func canonicalPath(path string) (string, os.FileInfo, error) {
	value, err := filepath.Abs(path)
	if err != nil {
		return "", nil, fmt.Errorf("absolute root: %w", err)
	}
	value, err = filepath.EvalSymlinks(value)
	if err != nil {
		return "", nil, fmt.Errorf("canonical root: %w", err)
	}
	info, err := os.Stat(value)
	if err != nil {
		return "", nil, fmt.Errorf("stat root: %w", err)
	}
	return filepath.Clean(value), info, nil
}

func storageRoot() (string, error) {
	if value := os.Getenv("YGG_STORAGE_ROOT"); value != "" {
		return filepath.Abs(value)
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", fmt.Errorf("resolve home directory: %w", err)
	}
	return filepath.Join(home, ".local", "share", "ygg"), nil
}
