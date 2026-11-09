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

// projectMorePaths bounds the uncited ranked paths kept per repository. On the
// synthetic hub-and-spoke suite five paths per repository raised shown spoke
// files from 100 to 137 of 153 queries, at the cost of paths only.
const projectMorePaths = 5

// Allocate citations round-robin across repositories without comparing their
// local ranking scores. Keep each repository's own order within its group.
// Records beyond a repository's allocation, then its own more paths, become a
// short window of uncited paths.
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
		result := &repos[i].Result
		seen := make(map[string]bool, counts[i])
		for _, record := range result.Records[:counts[i]] {
			seen[record.Path] = true
		}
		var more []string
		candidates := make([]string, 0, len(result.Records)-counts[i]+len(result.MorePaths))
		for _, record := range result.Records[counts[i]:] {
			candidates = append(candidates, record.Path)
		}
		candidates = append(candidates, result.MorePaths...)
		for _, path := range candidates {
			if len(more) == projectMorePaths {
				break
			}
			if !seen[path] {
				seen[path] = true
				more = append(more, path)
			}
		}
		result.Records = result.Records[:counts[i]]
		result.MorePaths = more
	}
}
