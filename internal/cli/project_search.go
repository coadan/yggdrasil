package cli

import (
	"context"
	"fmt"
	"io"
	"maps"
	"path/filepath"
	"slices"
	"strings"

	"github.com/coadan/yggdrasil/internal/config"
	"github.com/coadan/yggdrasil/internal/contracts"
	"github.com/coadan/yggdrasil/internal/project"
	"github.com/coadan/yggdrasil/query"
)

type projectResult struct {
	Schema       string             `json:"schema"`
	Project      string             `json:"project"`
	Branch       string             `json:"branch,omitempty"`
	Repositories []repositoryResult `json:"repositories"`
}

type repositoryResult struct {
	Alias     string                    `json:"alias"`
	Root      string                    `json:"root"`
	Head      string                    `json:"head,omitempty"`
	Selection string                    `json:"selection"`
	Warnings  []project.CheckoutWarning `json:"warnings,omitempty"`
	Result    query.Result              `json:"result"`
}

func (r *runner) runProjectSearch(ctx context.Context, name, text string, opts query.Options,
	format string, progressOutput io.Writer,
) int {
	members, err := config.LoadProject(name)
	if err != nil {
		return r.fail(true, 2, err)
	}
	branch := project.CurrentBranch(ctx)
	result := projectResult{
		Schema: contracts.ProjectSearchSchema, Project: name, Branch: strings.TrimPrefix(branch, "refs/heads/"),
		Repositories: make([]repositoryResult, 0, len(members)),
	}
	for _, alias := range slices.Sorted(maps.Keys(members)) {
		if err := ctx.Err(); err != nil {
			return r.failSearch(err)
		}
		member := members[alias]
		if strings.TrimSpace(alias) == "" || !filepath.IsAbs(member.Root) {
			return r.fail(true, 2, fmt.Errorf("project %q requires a repository alias and absolute checkout path for %q", name, alias))
		}
		base, err := project.Resolve(member.Root)
		if err != nil {
			return r.fail(true, 2, fmt.Errorf("repository %q: %w", alias, err))
		}
		if base.Scope != "" {
			return r.fail(true, 2, fmt.Errorf("repository %q requires a Git checkout root", alias))
		}
		selected, err := project.SelectWorktree(ctx, base, branch)
		if err != nil {
			return r.fail(true, 2, fmt.Errorf("repository %q: %w", alias, err))
		}
		entry := repositoryResult{Alias: alias, Root: selected.Root, Head: selected.Head, Selection: "branch"}
		if selected.Root == base.Root {
			entry.Selection = "base"
			entry.Warnings = project.BaseWarnings(ctx, selected.Root, member.Upstream)
		}
		cfg, err := config.Load(selected.Root)
		if err != nil {
			return r.fail(true, 2, fmt.Errorf("repository %q: %w", alias, err))
		}
		entry.Result, err = searchRepository(ctx, selected, cfg, text, opts, progressOutput)
		if err != nil {
			return r.failSearch(fmt.Errorf("repository %q: %w", alias, err))
		}
		result.Repositories = append(result.Repositories, entry)
	}
	limitProjectRecords(result.Repositories, opts.Limit)
	if format == "json" {
		return r.writeJSON(envelope{Schema: contracts.CLIEnvelopeSchema, OK: true, Data: result})
	}
	var output strings.Builder
	for _, repo := range result.Repositories {
		fmt.Fprintf(&output, "%s (%s) %s\n", compactPath(repo.Alias), repo.Selection, compactPath(repo.Root))
		for _, warning := range repo.Warnings {
			fmt.Fprintf(&output, "warning: %s\n", compactExcerptLine(warning.Message))
		}
		appendCompactSearch(&output, repo.Result)
	}
	return r.writeCompactSearch(&output)
}

// Allocate citations round-robin across repositories without comparing their
// local ranking scores. Keep each repository's own order within its group.
func limitProjectRecords(repos []repositoryResult, limit int) {
	counts := make([]int, len(repos))
	for rank := 0; limit > 0; rank++ {
		allocated := false
		for i := range repos {
			if rank >= len(repos[i].Result.Records) {
				continue
			}
			counts[i]++
			limit--
			allocated = true
			if limit == 0 {
				break
			}
		}
		if !allocated {
			break
		}
	}
	for i := range repos {
		repos[i].Result.Records = repos[i].Result.Records[:counts[i]]
		repos[i].Result.MorePaths = nil
	}
}
