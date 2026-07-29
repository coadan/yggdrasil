package indexer

import (
	"context"
	"errors"
	"os"
	"syscall"

	"github.com/coadan/yggdrasil/internal/project"
)

func pruneRetiredIndexes(
	ctx context.Context,
	paths project.Paths,
) (pruned int, bytes int64, skipped int) {
	candidates, err := project.RetiredIndexes(ctx, paths)
	if err != nil {
		return 0, 0, 1
	}
	for _, candidate := range candidates {
		lock, err := os.OpenFile(candidate.IndexLock, os.O_CREATE|os.O_RDWR, 0o600)
		if err != nil {
			skipped++
			continue
		}
		if err := syscall.Flock(int(lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
			lock.Close()
			skipped++
			continue
		}
		// A newly recreated root is no longer retired even if it was absent
		// during family enumeration.
		if _, err := os.Stat(candidate.Root); err == nil || !errors.Is(err, os.ErrNotExist) {
			syscall.Flock(int(lock.Fd()), syscall.LOCK_UN)
			lock.Close()
			continue
		}
		removed := true
		for _, path := range []string{
			candidate.Database, candidate.Database + "-wal", candidate.Database + "-shm",
		} {
			if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
				removed = false
				break
			}
		}
		if removed {
			if err := os.Remove(candidate.FamilyMarker); err != nil &&
				!errors.Is(err, os.ErrNotExist) {
				removed = false
			}
		}
		syscall.Flock(int(lock.Fd()), syscall.LOCK_UN)
		lock.Close()
		if !removed {
			skipped++
			continue
		}
		_ = os.Remove(candidate.IndexLock)
		_ = os.Remove(candidate.StateDir)
		pruned++
		bytes += candidate.Bytes
	}
	return pruned, bytes, skipped
}
