// Package project resolves repository roots and their central state paths.
package project

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

type Paths struct {
	Root      string
	ID        string
	StateDir  string
	Database  string
	IndexLock string
}

type IndexSeed struct {
	Root     string
	Database string
}

func ResolveRoot(explicit string) (string, error) {
	if explicit != "" {
		return canonicalDir(explicit)
	}
	if output, err := exec.Command("git", "rev-parse", "--show-toplevel").Output(); err == nil {
		return canonicalDir(strings.TrimSpace(string(output)))
	}
	return canonicalDir(".")
}

func Resolve(explicit string) (Paths, error) {
	root, err := ResolveRoot(explicit)
	if err != nil {
		return Paths{}, err
	}
	storageRoot, err := storageRoot()
	if err != nil {
		return Paths{}, err
	}
	return pathsForRoot(root, storageRoot), nil
}

// SiblingIndexes returns existing indexes for other linked worktrees, preferring
// a worktree at the same commit so the seed requires the least reconciliation.
func SiblingIndexes(ctx context.Context, paths Paths) ([]IndexSeed, error) {
	output, err := exec.CommandContext(
		ctx, "git", "-C", paths.Root, "worktree", "list", "--porcelain", "-z",
	).Output()
	if err != nil {
		return nil, nil
	}
	type worktree struct {
		root string
		head string
	}
	var worktrees []worktree
	var current worktree
	var item worktree
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
	storage := filepath.Dir(filepath.Dir(paths.StateDir))
	var sameHead, other []IndexSeed
	for _, candidate := range worktrees {
		if candidate.root == paths.Root {
			continue
		}
		candidatePaths := pathsForRoot(candidate.root, storage)
		info, statErr := os.Stat(candidatePaths.Database)
		if statErr != nil || info.IsDir() || info.Size() == 0 {
			continue
		}
		seed := IndexSeed{Root: candidate.root, Database: candidatePaths.Database}
		if current.head != "" && candidate.head == current.head {
			sameHead = append(sameHead, seed)
		} else {
			other = append(other, seed)
		}
	}
	return append(sameHead, other...), nil
}

func pathsForRoot(root, storageRoot string) Paths {
	sum := sha256.Sum256([]byte(root))
	id := hex.EncodeToString(sum[:])[:24]
	stateDir := filepath.Join(storageRoot, "indexes", id)
	return Paths{
		Root:      root,
		ID:        id,
		StateDir:  stateDir,
		Database:  filepath.Join(stateDir, "search.sqlite3"),
		IndexLock: filepath.Join(stateDir, "index.lock"),
	}
}

func canonicalDir(path string) (string, error) {
	value, err := filepath.Abs(path)
	if err != nil {
		return "", fmt.Errorf("absolute root: %w", err)
	}
	value, err = filepath.EvalSymlinks(value)
	if err != nil {
		return "", fmt.Errorf("canonical root: %w", err)
	}
	info, err := os.Stat(value)
	if err != nil {
		return "", fmt.Errorf("stat root: %w", err)
	}
	if !info.IsDir() {
		return "", errors.New("repository root is not a directory")
	}
	return filepath.Clean(value), nil
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
