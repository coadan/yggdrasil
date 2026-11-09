package project

import (
	"context"
	"fmt"
	"os/exec"
	"strconv"
	"strings"
)

// CurrentBranch returns the full Git branch ref for the invocation directory.
// A detached checkout or a directory outside Git has no branch context.
func CurrentBranch(ctx context.Context) string {
	output, err := exec.CommandContext(ctx, "git", "symbolic-ref", "--quiet", "HEAD").Output()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(output))
}

// SelectWorktree selects an available same-branch worktree in base's Git
// family. When branch is empty or there is no matching worktree, it uses base.
func SelectWorktree(ctx context.Context, base Paths, branch string) (Paths, error) {
	worktrees, err := linkedWorktrees(ctx, base.Root)
	if err != nil {
		return Paths{}, err
	}
	baseFound := false
	match := ""
	for _, tree := range worktrees {
		if tree.bare || tree.prunable {
			continue
		}
		if tree.root == base.Root {
			baseFound = true
		}
		if branch != "" && tree.branch == branch {
			if match != "" {
				return Paths{}, fmt.Errorf("multiple worktrees have branch %q", branch)
			}
			match = tree.root
		}
	}
	if !baseFound {
		return Paths{}, fmt.Errorf("%q is not an available Git checkout", base.Root)
	}
	if match == "" || match == base.Root {
		return base, nil
	}
	selected, err := Resolve(match)
	if err != nil {
		return Paths{}, err
	}
	if selected.FamilyID != base.FamilyID || selected.Scope != "" {
		return Paths{}, fmt.Errorf("worktree %q no longer belongs to %q", match, base.Root)
	}
	return selected, nil
}

type CheckoutWarning struct {
	Code     string `json:"code"`
	Message  string `json:"message"`
	Upstream string `json:"upstream,omitempty"`
	Behind   int    `json:"behind,omitempty"`
}

// BaseWarnings reports local edits and drift from the last fetched ref.
func BaseWarnings(ctx context.Context, root, upstream string) []CheckoutWarning {
	var warnings []CheckoutWarning
	status, err := localGitOutput(ctx, root, "status", "--porcelain=v1", "-z", "--untracked-files=normal")
	if err != nil {
		warnings = append(warnings, CheckoutWarning{
			Code: "status-unavailable", Message: "local checkout status is unavailable",
		})
	} else if len(status) > 0 {
		warnings = append(warnings, CheckoutWarning{
			Code: "dirty-base", Message: "base checkout has local edits",
		})
	}
	if upstream == "" {
		return warnings
	}
	output, err := localGitOutput(ctx, root, "rev-list", "--count", "HEAD.."+upstream, "--")
	behind, parseErr := strconv.Atoi(strings.TrimSpace(string(output)))
	if err != nil || parseErr != nil {
		return append(warnings, CheckoutWarning{
			Code: "upstream-unavailable", Message: fmt.Sprintf("cannot compare base with local ref %q", upstream),
			Upstream: upstream,
		})
	}
	if behind > 0 {
		warnings = append(warnings, CheckoutWarning{
			Code: "behind-base", Message: fmt.Sprintf("base checkout is %d commits behind local ref %q", behind, upstream),
			Upstream: upstream, Behind: behind,
		})
	}
	return warnings
}

func localGitOutput(ctx context.Context, root string, args ...string) ([]byte, error) {
	command := exec.CommandContext(ctx, "git", append([]string{"--no-optional-locks", "-C", root}, args...)...)
	command.Env = append(command.Environ(), "GIT_NO_LAZY_FETCH=1")
	return command.Output()
}
