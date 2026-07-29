// Package search ranks bounded records from the canonical SQLite index.
package search

import (
	"context"
	"errors"
	"fmt"
	"sort"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/coadan/yggdrasil/internal/config"
	"github.com/coadan/yggdrasil/internal/contracts"
	"github.com/coadan/yggdrasil/internal/embedding"
	"github.com/coadan/yggdrasil/internal/store"
)

const rrfK = 60.0
const maxPathTerms = 6
const maxStructuredAnchors = 4
const maxMorePaths = 20
const extractorLaneWeight = 0.5

const (
	MaxResults    = 100
	MaxQueryBytes = 16 * 1024
	MaxQueryTerms = 256
)

var ErrSemanticUnavailable = errors.New("semantic search is unavailable")

type Options struct {
	Mode          string
	Limit         int
	Root          string
	Scope         string
	HasExtractors bool
	Embedding     *config.Embedding
}

type Result struct {
	Schema         string         `json:"schema"`
	Query          string         `json:"query"`
	RequestedMode  string         `json:"requestedMode"`
	ActiveMode     string         `json:"activeMode"`
	FallbackReason string         `json:"fallbackReason,omitempty"`
	ElapsedMS      int64          `json:"elapsedMs"`
	Records        []RankedRecord `json:"records"`
	MorePaths      []string       `json:"morePaths,omitempty"`
}

type RankedRecord struct {
	Path       string         `json:"path"`
	StartLine  int            `json:"startLine"`
	EndLine    int            `json:"endLine"`
	Kind       string         `json:"kind"`
	Title      string         `json:"title,omitempty"`
	Excerpt    string         `json:"excerpt"`
	Metadata   map[string]any `json:"metadata,omitempty"`
	Source     string         `json:"source"`
	Retrieval  []string       `json:"retrieval"`
	Score      float64        `json:"score"`
	internalID int64
}

func Run(ctx context.Context, value *store.Store, query string, opts Options) (Result, error) {
	started := time.Now()
	query = strings.TrimSpace(query)
	if query == "" {
		return Result{}, errors.New("search query is required")
	}
	if !utf8.ValidString(query) {
		return Result{}, errors.New("search query must be valid UTF-8")
	}
	if len(query) > MaxQueryBytes {
		return Result{}, fmt.Errorf("search query exceeds %d bytes", MaxQueryBytes)
	}
	if len(strings.Fields(query)) > MaxQueryTerms {
		return Result{}, fmt.Errorf("search query exceeds %d terms", MaxQueryTerms)
	}
	if opts.Limit == 0 {
		opts.Limit = 10
	}
	if opts.Limit < 0 || opts.Limit > MaxResults {
		return Result{}, fmt.Errorf("search limit must be between 1 and %d", MaxResults)
	}
	if opts.Mode == "" {
		opts.Mode = "auto"
	}
	if opts.Mode != "auto" && opts.Mode != "lexical" && opts.Mode != "semantic" {
		return Result{}, fmt.Errorf("unsupported search mode %q", opts.Mode)
	}
	candidateLimit := max(100, opts.Limit*10)
	var lanes []lane
	structured := false
	if opts.Mode != "semantic" {
		terms := queryTerms(query)
		literal := literalTermQuery(query, terms)
		structured = literal
		if literal {
			records, err := value.LiteralCandidates(
				ctx, ftsAnyQuery(terms), query, opts.Scope, candidateLimit,
			)
			if err != nil {
				return Result{}, fmt.Errorf("literal search: %w", err)
			}
			lanes = append(lanes, lane{name: "literal", records: records})
		} else if len(terms) > 1 {
			phrase, err := value.LexicalCandidates(
				ctx, ftsPhraseQuery(terms), opts.Scope, candidateLimit,
			)
			if err != nil {
				return Result{}, fmt.Errorf("exact lexical search: %w", err)
			}
			lanes = append(lanes, lane{name: "exact", records: phrase})
			if !structured {
				all, err := value.LexicalCandidates(
					ctx, ftsAllQuery(terms), opts.Scope, candidateLimit,
				)
				if err != nil {
					return Result{}, fmt.Errorf("all-term lexical search: %w", err)
				}
				lanes = append(lanes, lane{name: "all-terms", records: all})
			}
		}
		if !structured {
			fts, err := value.LexicalCandidates(
				ctx, ftsAnyQuery(terms), opts.Scope, candidateLimit,
			)
			if err != nil {
				return Result{}, fmt.Errorf("lexical search: %w", err)
			}
			paths, err := value.PathCandidates(
				ctx, pathTerms(query), opts.Scope, candidateLimit,
			)
			if err != nil {
				return Result{}, fmt.Errorf("path search: %w", err)
			}
			lanes = append(lanes,
				lane{name: "lexical", records: fts},
				lane{name: "path", records: paths},
			)
			if opts.HasExtractors {
				extracted, err := value.ExtractorCandidates(
					ctx, ftsAnyQuery(terms), opts.Scope, candidateLimit,
				)
				if err != nil {
					return Result{}, fmt.Errorf("extractor search: %w", err)
				}
				lanes = append(lanes, lane{name: "extractor", records: extracted})
			}
			for _, anchor := range structuredAnchorQueries(query) {
				records, err := value.LexicalCandidates(
					ctx, anchor, opts.Scope, candidateLimit,
				)
				if err != nil {
					return Result{}, fmt.Errorf("structured anchor search: %w", err)
				}
				lanes = append(lanes, lane{name: "anchor", records: records})
			}
		}
	}
	result := Result{
		Schema:        contracts.SearchSchema,
		Query:         query,
		RequestedMode: opts.Mode,
		ActiveMode:    "lexical",
	}
	if opts.Mode == "lexical" {
		setResultRecords(&result, query, opts.Limit, lanes)
		result.ElapsedMS = time.Since(started).Milliseconds()
		return result, nil
	}
	if structured && opts.Mode == "auto" {
		setResultRecords(&result, query, opts.Limit, lanes)
		result.ElapsedMS = time.Since(started).Milliseconds()
		return result, nil
	}
	if opts.Embedding == nil {
		if opts.Mode == "semantic" {
			return Result{}, fmt.Errorf("%w: no embedding provider is configured", ErrSemanticUnavailable)
		}
		if opts.Mode == "auto" {
			result.FallbackReason = "semantic-unconfigured"
		}
		setResultRecords(&result, query, opts.Limit, lanes)
		result.ElapsedMS = time.Since(started).Milliseconds()
		return result, nil
	}
	fingerprint := embedding.Fingerprint(*opts.Embedding)
	state, err := value.EmbeddingState(ctx, fingerprint)
	if err != nil {
		return Result{}, fmt.Errorf("inspect embedding lane: %w", err)
	}
	if !state.Complete {
		if opts.Mode == "semantic" {
			return Result{}, fmt.Errorf(
				"%w: index has %d of %d current vectors",
				ErrSemanticUnavailable, state.Embedded, state.Records,
			)
		}
		result.FallbackReason = "semantic-incomplete"
		setResultRecords(&result, query, opts.Limit, lanes)
		result.ElapsedMS = time.Since(started).Milliseconds()
		return result, nil
	}
	provider, err := embedding.New(ctx, opts.Root, *opts.Embedding)
	if err != nil {
		return semanticFailure(result, opts, lanes, started, err)
	}
	values, embedErr := provider.Embed(ctx, []embedding.Input{{ID: "query", Text: query}})
	closeErr := provider.Close()
	if embedErr != nil {
		return semanticFailure(result, opts, lanes, started, embedErr)
	}
	if closeErr != nil {
		return semanticFailure(result, opts, lanes, started, closeErr)
	}
	if len(values) != 1 || values[0].ID != "query" {
		return semanticFailure(result, opts, lanes, started, errors.New("provider returned an invalid query embedding"))
	}
	vectors, err := value.VectorCandidates(ctx, values[0].Vector, opts.Scope, candidateLimit)
	if err != nil {
		return Result{}, fmt.Errorf("semantic search: %w", err)
	}
	lanes = append(lanes, lane{name: "semantic", records: vectors})
	if opts.Mode == "semantic" {
		result.ActiveMode = "semantic"
	} else {
		result.ActiveMode = "hybrid"
	}
	setResultRecords(&result, query, opts.Limit, lanes)
	result.ElapsedMS = time.Since(started).Milliseconds()
	return result, nil
}

func semanticFailure(
	result Result,
	opts Options,
	lanes []lane,
	started time.Time,
	cause error,
) (Result, error) {
	if opts.Mode == "semantic" {
		return Result{}, fmt.Errorf("%w: %v", ErrSemanticUnavailable, cause)
	}
	result.FallbackReason = "semantic-provider-error"
	setResultRecords(&result, result.Query, opts.Limit, lanes)
	result.ElapsedMS = time.Since(started).Milliseconds()
	return result, nil
}

func setResultRecords(result *Result, query string, limit int, lanes []lane) {
	ranked := fuse(query, min(MaxResults, limit+maxMorePaths), lanes)
	if len(ranked) <= limit {
		result.Records = ranked
		return
	}
	result.Records = ranked[:limit]
	result.MorePaths = make([]string, 0, len(ranked)-limit)
	for _, record := range ranked[limit:] {
		result.MorePaths = append(result.MorePaths, record.Path)
	}
}

type lane struct {
	name    string
	records []store.Record
}

type fused struct {
	record    store.Record
	score     float64
	retrieval map[string]bool
	evidence  citationEvidence
}

func fuse(query string, limit int, lanes []lane) []RankedRecord {
	values := map[int64]*fused{}
	pathEvidence := make(map[string]*fused)
	for _, candidateLane := range lanes {
		for rank, record := range candidateLane.records {
			score := 1.0 / (rrfK + float64(rank+1))
			if candidateLane.name == "extractor" {
				score *= extractorLaneWeight
			}
			if candidateLane.name == "path" {
				pathEvidence[record.Path] = &fused{record: record, score: score}
				continue
			}
			value := values[record.ID]
			if value == nil {
				value = &fused{record: record, retrieval: map[string]bool{}}
				values[record.ID] = value
			}
			value.score += score
			value.retrieval[candidateLane.name] = true
		}
	}
	for path, evidence := range pathEvidence {
		var best *fused
		for _, value := range values {
			if value.record.Path != path || value.record.Kind == "file" {
				continue
			}
			if best == nil || value.score > best.score ||
				(value.score == best.score && value.record.StartLine < best.record.StartLine) ||
				(value.score == best.score && value.record.StartLine == best.record.StartLine &&
					value.record.ID < best.record.ID) {
				best = value
			}
		}
		if best == nil {
			best = values[evidence.record.ID]
			if best == nil {
				best = &fused{record: evidence.record, retrieval: map[string]bool{}}
				values[evidence.record.ID] = best
			}
		}
		best.score += evidence.score
		best.retrieval["path"] = true
	}
	ordered := make([]*fused, 0, len(values))
	hasCitedRecord := make(map[string]bool)
	for _, value := range values {
		value.evidence = locateEvidence(value.record.Text, query)
		ordered = append(ordered, value)
		if value.record.Kind != "file" {
			hasCitedRecord[value.record.Path] = true
		}
	}
	sort.Slice(ordered, func(i, j int) bool {
		if ordered[i].score != ordered[j].score {
			return ordered[i].score > ordered[j].score
		}
		if ordered[i].record.Path != ordered[j].record.Path {
			return ordered[i].record.Path < ordered[j].record.Path
		}
		if ordered[i].record.StartLine != ordered[j].record.StartLine {
			return ordered[i].record.StartLine < ordered[j].record.StartLine
		}
		return ordered[i].record.ID < ordered[j].record.ID
	})
	eligible := make([]*fused, 0, len(ordered))
	selectedPaths := make(map[string]bool)
	citations := make(map[string]*fused)
	for _, value := range ordered {
		if value.record.Kind != "file" &&
			betterCitation(value, citations[value.record.Path]) {
			citations[value.record.Path] = value
		}
		if selectedPaths[value.record.Path] {
			continue
		}
		if value.record.Kind == "file" && hasCitedRecord[value.record.Path] {
			continue
		}
		eligible = append(eligible, value)
		selectedPaths[value.record.Path] = true
	}
	promoteThirdRoot(eligible)
	result := make([]RankedRecord, 0, min(limit, len(eligible)))
	for _, value := range eligible {
		if len(result) == limit {
			break
		}
		citation := citations[value.record.Path]
		if citation == nil {
			citation = value
		}
		retrievalSet := make(map[string]bool, len(value.retrieval)+len(citation.retrieval))
		for name := range value.retrieval {
			retrievalSet[name] = true
		}
		for name := range citation.retrieval {
			retrievalSet[name] = true
		}
		retrieval := make([]string, 0, len(retrievalSet))
		for name := range retrievalSet {
			retrieval = append(retrieval, name)
		}
		sort.Strings(retrieval)
		startLine, endLine, title, citationExcerpt :=
			localizedCitation(citation.record, citation.evidence)
		result = append(result, RankedRecord{
			Path:       citation.record.Path,
			StartLine:  startLine,
			EndLine:    endLine,
			Kind:       citation.record.Kind,
			Title:      title,
			Excerpt:    citationExcerpt,
			Metadata:   citation.record.Metadata,
			Source:     citation.record.Source,
			Retrieval:  retrieval,
			Score:      value.score,
			internalID: citation.record.ID,
		})
	}
	return result
}

type citationEvidence struct {
	terms   int
	line    int
	literal bool
}

func betterCitation(candidate, current *fused) bool {
	if current == nil {
		return true
	}
	candidateEvidence := candidate.evidence
	currentEvidence := current.evidence
	if candidateEvidence.literal != currentEvidence.literal {
		return candidateEvidence.literal
	}
	if candidateEvidence.terms != currentEvidence.terms {
		return candidateEvidence.terms > currentEvidence.terms
	}
	if candidateEvidence.terms == 0 {
		return false
	}
	candidatePriority := citationLanePriority(candidate.retrieval)
	currentPriority := citationLanePriority(current.retrieval)
	if candidatePriority != currentPriority {
		return candidatePriority > currentPriority
	}
	candidateLine := candidate.record.StartLine + candidateEvidence.line
	currentLine := current.record.StartLine + currentEvidence.line
	if candidateLine != currentLine {
		return candidateLine < currentLine
	}
	if candidate.score != current.score {
		return candidate.score > current.score
	}
	return candidate.record.ID < current.record.ID
}

func citationLanePriority(retrieval map[string]bool) int {
	for index, name := range []string{"literal", "exact", "all-terms", "anchor", "extractor", "lexical"} {
		if retrieval[name] {
			return 6 - index
		}
	}
	return 0
}

func locateEvidence(text, query string) citationEvidence {
	if line := literalEvidenceLine(text, query); line >= 0 {
		return citationEvidence{terms: 1, line: line, literal: true}
	}
	lines := strings.Split(text, "\n")
	groups := evidenceTermGroups(query)
	lineTerms := make([]map[string]bool, len(lines))
	for index, line := range lines {
		lineTerms[index] = make(map[string]bool)
		for _, term := range evidenceTerms(line) {
			lineTerms[index][term] = true
		}
	}
	best := citationEvidence{line: -1}
	for line := range lines {
		start := max(0, line-2)
		end := min(len(lines), line+3)
		matched := 0
		for _, group := range groups {
			found := false
			for _, term := range group {
				for _, candidate := range lineTerms[start:end] {
					if candidate[term] {
						found = true
						break
					}
				}
				if found {
					break
				}
			}
			if found {
				matched++
			}
		}
		if matched > best.terms {
			best = citationEvidence{terms: matched, line: line}
		}
	}
	return best
}

func literalEvidenceLine(text, query string) int {
	if !literalTermQuery(query, queryTerms(query)) {
		return -1
	}
	for index, line := range strings.Split(text, "\n") {
		if strings.Contains(line, query) {
			return index
		}
	}
	return -1
}

func evidenceTerms(value string) []string {
	seen := make(map[string]bool)
	var result []string
	for _, group := range evidenceTermGroups(value) {
		for _, term := range group {
			if seen[term] {
				continue
			}
			seen[term] = true
			result = append(result, term)
		}
	}
	return result
}

func evidenceTermGroups(value string) [][]string {
	seen := make(map[string]bool)
	var result [][]string
	for _, token := range queryTerms(value) {
		key := strings.ToLower(token)
		if seen[key] {
			continue
		}
		seen[key] = true
		result = append(result, tokenEvidenceTerms(token))
	}
	return result
}

func tokenEvidenceTerms(token string) []string {
	seen := make(map[string]bool)
	var result []string
	add := func(term string) {
		term = strings.ToLower(term)
		if term == "" || seen[term] {
			return
		}
		seen[term] = true
		result = append(result, term)
	}
	add(token)
	runes := []rune(token)
	start := 0
	for index := 1; index < len(runes); index++ {
		boundary := unicode.IsUpper(runes[index]) &&
			(unicode.IsLower(runes[index-1]) ||
				(index+1 < len(runes) && unicode.IsLower(runes[index+1])))
		if !boundary {
			continue
		}
		add(string(runes[start:index]))
		start = index
	}
	add(string(runes[start:]))
	return result
}

func localizedCitation(
	record store.Record,
	evidence citationEvidence,
) (int, int, string, string) {
	lines := strings.Split(record.Text, "\n")
	if evidence.terms == 0 || evidence.line < 0 ||
		record.EndLine-record.StartLine+1 < len(lines) {
		return record.StartLine, record.EndLine, record.Title, excerpt(record.Text)
	}
	start := max(0, evidence.line-2)
	text := excerpt(strings.Join(lines[start:], "\n"))
	startLine := record.StartLine + start
	endLine := min(record.EndLine, startLine+strings.Count(text, "\n"))
	title := record.Title
	if title == fmt.Sprintf("%s:%d", record.Path, record.StartLine) {
		title = fmt.Sprintf("%s:%d", record.Path, startLine)
	}
	return startLine, endLine, title, text
}

func promoteThirdRoot(values []*fused) {
	if len(values) < 3 {
		return
	}
	firstRoot := pathRoot(values[0].record.Path)
	if pathRoot(values[1].record.Path) != firstRoot {
		return
	}
	for index := 2; index < len(values); index++ {
		if pathRoot(values[index].record.Path) == firstRoot {
			continue
		}
		value := values[index]
		copy(values[3:index+1], values[2:index])
		values[2] = value
		return
	}
}

func pathRoot(value string) string {
	if index := strings.IndexByte(value, '/'); index >= 0 {
		return value[:index]
	}
	return "."
}

func queryTerms(query string) []string {
	return strings.FieldsFunc(query, func(value rune) bool {
		return !unicode.IsLetter(value) && !unicode.IsDigit(value)
	})
}

func literalTermQuery(query string, terms []string) bool {
	return len(strings.Fields(query)) == 1 &&
		len(terms) > 0 &&
		strings.ContainsAny(query, "-_.:/#<>=\"'`")
}

func structuredAnchorQueries(query string) []string {
	if len(strings.Fields(query)) < 2 {
		return nil
	}
	seen := make(map[string]bool)
	var result []string
	singleIdentifier := false
	for _, field := range strings.Fields(query) {
		terms := queryTerms(field)
		if len(terms) == 0 || !structuredAnchorField(field, terms) {
			continue
		}
		anchor := ftsPhraseQuery(terms)
		if seen[anchor] {
			continue
		}
		seen[anchor] = true
		result = append(result, anchor)
		singleIdentifier = structuredIdentifierField(field)
		if len(result) == maxStructuredAnchors {
			break
		}
	}
	if len(result) == 1 && !singleIdentifier {
		return nil
	}
	return result
}

func structuredAnchorField(field string, terms []string) bool {
	if len(terms) > 1 && strings.ContainsAny(field, "-_.:/#") {
		return true
	}
	return structuredIdentifierField(field)
}

func structuredIdentifierField(field string) bool {
	hasLower := false
	hasInternalUpper := false
	for index, value := range field {
		hasLower = hasLower || unicode.IsLower(value)
		hasInternalUpper = hasInternalUpper || (index > 0 && unicode.IsUpper(value))
	}
	return hasLower && hasInternalUpper
}

func pathTerms(query string) []string {
	seen := make(map[string]bool)
	var result []string
	for _, field := range strings.FieldsFunc(strings.ToLower(query), func(value rune) bool {
		return !unicode.IsLetter(value) && !unicode.IsDigit(value)
	}) {
		if utf8.RuneCountInString(field) < 4 || seen[field] {
			continue
		}
		seen[field] = true
		result = append(result, field)
		if len(result) == maxPathTerms {
			break
		}
	}
	return result
}

func quotedTerms(terms []string) []string {
	quoted := make([]string, 0, len(terms))
	for _, field := range terms {
		field = strings.ReplaceAll(field, `"`, `""`)
		quoted = append(quoted, `"`+field+`"`)
	}
	return quoted
}

func ftsAnyQuery(terms []string) string {
	return strings.Join(quotedTerms(terms), " OR ")
}

func ftsAllQuery(terms []string) string {
	return strings.Join(quotedTerms(terms), " AND ")
}

func ftsPhraseQuery(terms []string) string {
	fields := make([]string, 0, len(terms))
	for _, field := range terms {
		fields = append(fields, strings.ReplaceAll(field, `"`, `""`))
	}
	return `"` + strings.Join(fields, " ") + `"`
}

func excerpt(text string) string {
	text = strings.TrimSpace(text)
	const limit = 280
	if utf8.RuneCountInString(text) <= limit {
		return text
	}
	end := 0
	for range limit {
		_, size := utf8.DecodeRuneInString(text[end:])
		end += size
	}
	return strings.TrimSpace(text[:end]) + "…"
}
