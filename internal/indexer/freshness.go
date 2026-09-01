package indexer

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"hash"
	"os"
	"os/exec"
	"path/filepath"

	"github.com/coadan/yggdrasil/internal/config"
	"github.com/coadan/yggdrasil/internal/discovery"
	"github.com/coadan/yggdrasil/internal/embedding"
)

// FreshnessToken identifies the mechanically observable repository and
// extraction state represented by an index. Git repositories use HEAD plus
// dirty-path metadata so unchanged searches avoid a full discovery scan.
func FreshnessToken(ctx context.Context, root string, cfg config.Config) (string, error) {
	return freshnessToken(ctx, root, cfg, config.ExtractionFingerprint(cfg))
}

func freshnessToken(
	ctx context.Context,
	root string,
	cfg config.Config,
	extractionFingerprint string,
) (string, error) {
	digest := sha256.New()
	fmt.Fprintln(digest, extractionFingerprint)
	if cfg.Embedding == nil {
		fmt.Fprintln(digest, "embedding:none")
	} else {
		fmt.Fprintln(digest, embedding.Fingerprint(*cfg.Embedding))
	}
	if err := gitFreshness(ctx, digest, root); err == nil {
		return "sha256:" + hex.EncodeToString(digest.Sum(nil)), nil
	}
	candidates, err := discovery.Candidates(root, cfg.IgnoreGlobs)
	if err != nil {
		return "", fmt.Errorf("discover freshness state: %w", err)
	}
	for _, candidate := range candidates {
		fmt.Fprintf(
			digest,
			"%q %d %d\n",
			candidate.Path,
			candidate.Size,
			candidate.MTimeNS,
		)
	}
	return "sha256:" + hex.EncodeToString(digest.Sum(nil)), nil
}

func gitFreshness(ctx context.Context, digest hash.Hash, root string) error {
	head, err := exec.CommandContext(ctx, "git", "-C", root, "rev-parse", "HEAD").Output()
	if err != nil {
		head = []byte("unborn\n")
	}
	status, err := exec.CommandContext(
		ctx,
		"git", "-C", root, "status", "--porcelain=v1", "-z", "--untracked-files=all",
	).Output()
	if err != nil {
		return err
	}
	digest.Write(head)
	digest.Write(status)
	fields := bytes.Split(status, []byte{0})
	for index := 0; index < len(fields); index++ {
		field := fields[index]
		if len(field) < 4 {
			continue
		}
		writePathState(digest, root, field[3:])
		if (field[0] == 'R' || field[0] == 'C' || field[1] == 'R' || field[1] == 'C') &&
			index+1 < len(fields) {
			index++
			writePathState(digest, root, fields[index])
		}
	}
	return nil
}

func writePathState(digest hash.Hash, root string, path []byte) {
	info, err := os.Lstat(filepath.Join(root, filepath.FromSlash(string(path))))
	if err != nil {
		fmt.Fprintf(digest, "%q missing\n", path)
		return
	}
	fmt.Fprintf(
		digest,
		"%q %d %d %d\n",
		path,
		info.Mode(),
		info.Size(),
		info.ModTime().UnixNano(),
	)
}
