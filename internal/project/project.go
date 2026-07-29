// Package project resolves repository roots and their central state paths.
package project

import (
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
	sum := sha256.Sum256([]byte(root))
	id := hex.EncodeToString(sum[:])[:24]
	storageRoot, err := storageRoot()
	if err != nil {
		return Paths{}, err
	}
	stateDir := filepath.Join(storageRoot, "indexes", id)
	return Paths{
		Root:      root,
		ID:        id,
		StateDir:  stateDir,
		Database:  filepath.Join(stateDir, "search.sqlite3"),
		IndexLock: filepath.Join(stateDir, "index.lock"),
	}, nil
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
