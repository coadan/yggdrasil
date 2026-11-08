package query

import (
	"cmp"
	"context"
	"errors"
	"fmt"
	"os"
	"regexp"
	"slices"
	"strings"
	"time"

	"github.com/coadan/yggdrasil/internal/contracts"
	"github.com/coadan/yggdrasil/internal/discovery"
)

// FilesystemOptions bounds the live lexical fallback used while the durable
// index is being replaced. It deliberately has no semantic or graph inputs.
type FilesystemOptions struct {
	Limit          int
	Scope          string
	IgnoreGlobs    []string
	MaxFileBytes   int64
	RequestedMode  string
	FallbackReason string
	MatchKind      string
	About          string
}

type filesystemCandidate struct {
	record       Candidate
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
	if err := ctx.Err(); err != nil {
		return Result{}, err
	}
	query = strings.TrimSpace(query)
	if query == "" {
		return Result{}, errors.New("search query is required")
	}
	if opts.Limit == 0 {
		opts.Limit = 10
	}
	if opts.Limit < 0 || opts.Limit > MaxResults {
		return Result{}, fmt.Errorf("search limit must be between 1 and %d", MaxResults)
	}
	if opts.MaxFileBytes <= 0 {
		opts.MaxFileBytes = 4 * 1024 * 1024
	}
	if opts.RequestedMode == "" {
		opts.RequestedMode = "auto"
	}
	if opts.FallbackReason == "" {
		opts.FallbackReason = "index-busy"
	}
	plan, err := Parse(query, opts.MatchKind, opts.About, opts.Scope)
	if err != nil {
		return Result{}, err
	}
	var expression *regexp.Regexp
	if plan.Lexical.Kind == MatchRegexp {
		expression, err = regexp.Compile(plan.Lexical.Pattern)
		if err != nil {
			return Result{}, err
		}
	}
	candidates, err := discovery.Candidates(ctx, root, opts.IgnoreGlobs)
	if err != nil {
		return Result{}, err
	}
	candidateLimit := opts.Limit + maxMorePaths
	excerptLimit := resultExcerptLimit(opts.Limit)
	ranked := make([]filesystemCandidate, 0, min(len(candidates), candidateLimit))
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
		evidence, matched := filesystemEvidence(plan, file.Content, expression)
		pathEvidence := citationEvidence{line: -1}
		if plan.Lexical.Kind == MatchText &&
			!literalTermQuery(plan.Lexical.Pattern, queryTerms(plan.Lexical.Pattern)) {
			pathEvidence = locateEvidence(file.Path, query)
		}
		if !matched && pathEvidence.terms == 0 {
			continue
		}
		lineCount := strings.Count(file.Content, "\n") + 1
		value := filesystemCandidate{
			record: Candidate{
				ID: int64(index + 1), Path: file.Path,
				StartLine: 1, EndLine: lineCount, Kind: file.Kind,
				Title: file.Path, Text: file.Content, Source: "filesystem",
			},
			evidence: evidence, pathEvidence: pathEvidence,
			literal: evidence.literal,
		}
		position, _ := slices.BinarySearchFunc(ranked, value, compareFilesystemCandidates)
		if position >= candidateLimit {
			continue
		}
		// localizedCitation returns an independent bounded excerpt, so even
		// a short tail cannot keep the otherwise discarded file buffer alive.
		value.record.StartLine, value.record.EndLine, value.record.Title, value.record.Text =
			localizedCitation(value.record, value.evidence, excerptLimit)
		if len(ranked) < candidateLimit {
			ranked = append(ranked, filesystemCandidate{})
		}
		copy(ranked[position+1:], ranked[position:len(ranked)-1])
		ranked[position] = value
	}

	result := Result{
		Schema: contracts.SearchSchema, Query: query,
		RequestedMode: opts.RequestedMode, ActiveMode: "lexical",
		FallbackReason: opts.FallbackReason, ElapsedMS: time.Since(started).Milliseconds(),
		QueryPlan: plan,
	}
	resultLimit := min(opts.Limit, len(ranked))
	result.Records = make([]RankedRecord, 0, resultLimit)
	for _, candidate := range ranked[:resultLimit] {
		result.Records = append(result.Records, RankedRecord{
			Path: candidate.record.Path, StartLine: candidate.record.StartLine, EndLine: candidate.record.EndLine,
			Kind: candidate.record.Kind, Title: candidate.record.Title, Excerpt: candidate.record.Text,
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

func compareFilesystemCandidates(left, right filesystemCandidate) int {
	if left.literal != right.literal {
		if left.literal {
			return -1
		}
		return 1
	}
	if order := cmp.Compare(right.evidence.terms, left.evidence.terms); order != 0 {
		return order
	}
	if order := cmp.Compare(right.pathEvidence.terms, left.pathEvidence.terms); order != 0 {
		return order
	}
	if order := cmp.Compare(left.evidence.line, right.evidence.line); order != 0 {
		return order
	}
	if order := cmp.Compare(len(left.record.Path), len(right.record.Path)); order != 0 {
		return order
	}
	return strings.Compare(left.record.Path, right.record.Path)
}

func filesystemEvidence(plan Plan, text string, expression *regexp.Regexp) (citationEvidence, bool) {
	switch plan.Lexical.Kind {
	case MatchFixed:
		index := strings.Index(text, plan.Lexical.Pattern)
		if index < 0 {
			return citationEvidence{line: -1}, false
		}
		evidence := locateEvidence(text, plan.EvidenceText())
		evidence.literal = true
		return evidence.atMatch(text, matchSpan{start: index, end: index + len(plan.Lexical.Pattern)}), true
	case MatchRegexp:
		match := expression.FindStringIndex(text)
		if match == nil {
			return citationEvidence{line: -1}, false
		}
		evidence := locateEvidence(text, plan.EvidenceText())
		return evidence.atMatch(text, matchSpan{start: match[0], end: match[1]}), true
	default:
		pattern := plan.Lexical.Pattern
		terms := queryTerms(pattern)
		if len(terms) == 0 || literalTermQuery(pattern, terms) {
			start := literalOffset(text, pattern, codeIdentifierTermQuery(pattern, terms))
			if start < 0 {
				return citationEvidence{line: -1}, false
			}
			evidence := citationEvidence{literal: true}
			return evidence.atMatch(text, matchSpan{start: start, end: start + len(pattern)}), true
		}
		evidence := locateEvidence(text, pattern)
		return evidence, evidence.terms > 0
	}
}

func filesystemPathInScope(path, scope string) bool {
	scope = strings.TrimSuffix(scope, "/")
	return scope == "" || path == scope || strings.HasPrefix(path, scope+"/")
}
