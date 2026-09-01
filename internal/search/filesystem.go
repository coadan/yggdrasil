package search

import (
	"context"
	"errors"
	"os"
	"regexp"
	"sort"
	"strings"
	"time"

	"github.com/coadan/yggdrasil/internal/contracts"
	"github.com/coadan/yggdrasil/internal/discovery"
	"github.com/coadan/yggdrasil/internal/store"
	querycontract "github.com/coadan/yggdrasil/query"
)

// FilesystemOptions bounds the live lexical fallback used while the durable
// index is being replaced. It deliberately has no semantic or graph inputs.
type FilesystemOptions struct {
	Limit         int
	Scope         string
	IgnoreGlobs   []string
	MaxFileBytes  int64
	RequestedMode string
	MatchKind     string
	About         string
}

type filesystemCandidate struct {
	record       store.Record
	evidence     citationEvidence
	pathEvidence citationEvidence
	literal      bool
}

// RunFilesystem searches current working-tree files without consulting the
// SQLite index. This keeps default search available during an index run without
// returning records that were deleted or changed after the last commit.
func RunFilesystem(
	ctx context.Context,
	root, query string,
	opts FilesystemOptions,
) (Result, error) {
	started := time.Now()
	query = strings.TrimSpace(query)
	if query == "" {
		return Result{}, errors.New("search query is required")
	}
	if opts.Limit <= 0 {
		opts.Limit = 10
	}
	if opts.MaxFileBytes <= 0 {
		opts.MaxFileBytes = 4 * 1024 * 1024
	}
	if opts.RequestedMode == "" {
		opts.RequestedMode = "auto"
	}
	plan, err := querycontract.Parse(query, opts.MatchKind, opts.About, opts.Scope)
	if err != nil {
		return Result{}, err
	}
	candidates, err := discovery.Candidates(root, opts.IgnoreGlobs)
	if err != nil {
		return Result{}, err
	}
	ranked := make([]filesystemCandidate, 0, min(len(candidates), opts.Limit+maxMorePaths))
	for index, candidate := range candidates {
		if err := ctx.Err(); err != nil {
			return Result{}, err
		}
		if !filesystemPathInScope(candidate.Path, opts.Scope) {
			continue
		}
		file, skipped, readErr := discovery.Read(root, candidate, opts.MaxFileBytes)
		if readErr != nil {
			if errors.Is(readErr, os.ErrNotExist) {
				continue
			}
			continue
		}
		if skipped != nil {
			continue
		}
		evidence, matched := filesystemEvidence(plan, file.Content)
		pathEvidence := citationEvidence{line: -1}
		if plan.Lexical.Kind == querycontract.MatchText &&
			!literalTermQuery(plan.Lexical.Pattern, queryTerms(plan.Lexical.Pattern)) {
			pathEvidence = locateEvidence(file.Path, query)
		}
		if !matched && pathEvidence.terms == 0 {
			continue
		}
		lineCount := strings.Count(file.Content, "\n") + 1
		ranked = append(ranked, filesystemCandidate{
			record: store.Record{
				ID: int64(index + 1), Path: file.Path,
				StartLine: 1, EndLine: lineCount, Kind: file.Kind,
				Title: file.Path, Text: file.Content, Source: "filesystem",
			},
			evidence: evidence, pathEvidence: pathEvidence,
			literal: evidence.literal,
		})
	}
	sort.Slice(ranked, func(i, j int) bool {
		left, right := ranked[i], ranked[j]
		if left.literal != right.literal {
			return left.literal
		}
		if left.evidence.terms != right.evidence.terms {
			return left.evidence.terms > right.evidence.terms
		}
		if left.pathEvidence.terms != right.pathEvidence.terms {
			return left.pathEvidence.terms > right.pathEvidence.terms
		}
		if left.evidence.line != right.evidence.line {
			return left.evidence.line < right.evidence.line
		}
		if len(left.record.Path) != len(right.record.Path) {
			return len(left.record.Path) < len(right.record.Path)
		}
		return left.record.Path < right.record.Path
	})
	result := Result{
		Schema: contracts.SearchSchema, Query: query,
		RequestedMode: opts.RequestedMode, ActiveMode: "lexical",
		FallbackReason: "index-busy", ElapsedMS: time.Since(started).Milliseconds(),
		QueryPlan: plan,
	}
	excerptLimit := resultExcerptLimit(opts.Limit)
	resultLimit := min(opts.Limit, len(ranked))
	result.Records = make([]RankedRecord, 0, resultLimit)
	for _, candidate := range ranked[:resultLimit] {
		startLine, endLine, title, text := localizedCitation(
			candidate.record, candidate.evidence, excerptLimit,
		)
		result.Records = append(result.Records, RankedRecord{
			Path: candidate.record.Path, StartLine: startLine, EndLine: endLine,
			Kind: candidate.record.Kind, Title: title, Excerpt: text,
			Source: candidate.record.Source, Retrieval: []string{"filesystem"},
		})
	}
	for _, candidate := range ranked[resultLimit:] {
		if len(result.MorePaths) == maxMorePaths {
			break
		}
		result.MorePaths = append(result.MorePaths, candidate.record.Path)
	}
	return result, nil
}

func filesystemEvidence(plan querycontract.Plan, text string) (citationEvidence, bool) {
	switch plan.Lexical.Kind {
	case querycontract.MatchFixed:
		index := strings.Index(text, plan.Lexical.Pattern)
		if index < 0 {
			return citationEvidence{line: -1}, false
		}
		evidence := locateEvidence(text, plan.EvidenceText())
		evidence.literal = true
		evidence.line = strings.Count(text[:index], "\n")
		evidence.terms = max(1, evidence.terms)
		return evidence, true
	case querycontract.MatchRegexp:
		expression, err := regexp.Compile(plan.Lexical.Pattern)
		if err != nil {
			return citationEvidence{line: -1}, false
		}
		match := expression.FindStringIndex(text)
		if match == nil {
			return citationEvidence{line: -1}, false
		}
		evidence := locateEvidence(text, plan.EvidenceText())
		evidence.line = strings.Count(text[:match[0]], "\n")
		evidence.terms = max(1, evidence.terms)
		return evidence, true
	default:
		evidence := locateEvidence(text, plan.Lexical.Pattern)
		literal := literalTermQuery(plan.Lexical.Pattern, queryTerms(plan.Lexical.Pattern))
		if literal {
			return evidence, evidence.literal
		}
		return evidence, evidence.terms > 0
	}
}

func filesystemPathInScope(path, scope string) bool {
	scope = strings.TrimSuffix(scope, "/")
	return scope == "" || path == scope || strings.HasPrefix(path, scope+"/")
}
