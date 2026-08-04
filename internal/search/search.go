// Package search ranks bounded records from the canonical SQLite index.
package search

import (
	"context"
	"encoding/json"
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

const laneRRFK = 60.0
const familyRRFK = 10.0
const maxPathTerms = 6
const maxStructuredAnchors = 4
const maxMorePaths = 20
const extractorLaneWeight = 0.5
const graphFamilyWeight = 0.20
const graphSeedLimit = 20
const recordHeadResults = 1
const maxExcerptRunes = 280
const minExcerptRunes = 120
const targetExcerptRunes = 2400

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
	Kind       string         `json:"kind,omitempty"`
	Title      string         `json:"title,omitempty"`
	Excerpt    string         `json:"excerpt"`
	Metadata   map[string]any `json:"metadata,omitempty"`
	Source     string         `json:"source,omitempty"`
	Retrieval  []string       `json:"-"`
	Score      float64        `json:"-"`
	internalID int64
}

func (r RankedRecord) MarshalJSON() ([]byte, error) {
	type publicRecord struct {
		Path      string         `json:"path"`
		StartLine int            `json:"startLine"`
		EndLine   int            `json:"endLine"`
		Kind      string         `json:"kind,omitempty"`
		Title     string         `json:"title,omitempty"`
		Excerpt   string         `json:"excerpt"`
		Metadata  map[string]any `json:"metadata,omitempty"`
		Source    string         `json:"source,omitempty"`
	}
	kind := r.Kind
	if kind == "text-chunk" {
		kind = ""
	}
	title := r.Title
	if title == r.Path || title == fmt.Sprintf("%s:%d", r.Path, r.StartLine) {
		title = ""
	}
	source := r.Source
	if source == "core" {
		source = ""
	}
	return json.Marshal(publicRecord{
		Path: r.Path, StartLine: r.StartLine, EndLine: r.EndLine,
		Kind: kind, Title: title, Excerpt: r.Excerpt,
		Metadata: r.Metadata, Source: source,
	})
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
	if opts.Mode != "auto" && opts.Mode != "lexical" && opts.Mode != "semantic" && opts.Mode != "graph" {
		return Result{}, fmt.Errorf("unsupported search mode %q", opts.Mode)
	}
	candidateLimit := max(100, min(MaxResults+maxMorePaths, opts.Limit*10))
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
			if codeIdentifierTermQuery(query, terms) {
				records = identifierLiteralRecords(records, query)
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
	if opts.Mode == "graph" {
		graph, err := graphLane(ctx, value, query, lanes, opts.Scope, candidateLimit)
		if err != nil {
			return Result{}, err
		}
		result.ActiveMode = "graph"
		setResultRecords(&result, query, opts.Limit, []lane{graph})
		result.ElapsedMS = time.Since(started).Milliseconds()
		return result, nil
	}
	if structured && opts.Mode == "auto" {
		if opts.HasExtractors {
			graph, graphErr := graphLane(ctx, value, query, lanes, opts.Scope, candidateLimit)
			if graphErr != nil {
				return Result{}, graphErr
			}
			if len(graph.records) > 0 {
				lanes = append(lanes, graph)
				result.ActiveMode = "hybrid"
			}
		}
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
	values, embedErr := provider.Embed(ctx, []embedding.Input{{
		ID: "query", Text: embedding.QueryText(*opts.Embedding, query),
	}})
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
		if opts.HasExtractors {
			graph, graphErr := graphLane(ctx, value, query, lanes, opts.Scope, candidateLimit)
			if graphErr != nil {
				return Result{}, graphErr
			}
			lanes = append(lanes, graph)
		}
		result.ActiveMode = "hybrid"
	}
	setResultRecords(&result, query, opts.Limit, lanes)
	result.ElapsedMS = time.Since(started).Milliseconds()
	return result, nil
}

func graphLane(
	ctx context.Context,
	value *store.Store,
	query string,
	lanes []lane,
	scope string,
	limit int,
) (lane, error) {
	direct := make([]lane, 0, len(lanes))
	hasDirectRecords := false
	for _, candidate := range lanes {
		if candidate.name == "semantic" || candidate.name == "graph" {
			continue
		}
		direct = append(direct, candidate)
		hasDirectRecords = hasDirectRecords || len(candidate.records) > 0
	}
	if hasDirectRecords {
		lanes = direct
	}
	seedRecords := fuse(query, graphSeedLimit, maxExcerptRunes, lanes)
	seeds := make([]string, 0, len(seedRecords))
	for _, record := range seedRecords {
		seeds = append(seeds, record.Path)
	}
	records, err := value.GraphCandidates(ctx, seeds, scope, limit)
	if err != nil {
		return lane{}, fmt.Errorf("graph search: %w", err)
	}
	return lane{name: "graph", records: records}, nil
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
	excerptLimit := resultExcerptLimit(limit)
	ranked := fuse(
		query,
		min(MaxResults, limit+maxMorePaths),
		excerptLimit,
		lanes,
	)
	if result.RequestedMode == "auto" && result.ActiveMode == "hybrid" && limit > 1 {
		ranked = promoteGraphHead(ranked, query, excerptLimit, lanes, min(limit, 10)-1)
	}
	if len(ranked) <= limit {
		result.Records = ranked
		return
	}
	result.Records = ranked[:limit]
	result.MorePaths = make([]string, 0, min(maxMorePaths, len(ranked)-limit))
	for _, record := range ranked[limit:] {
		if len(result.MorePaths) == maxMorePaths {
			break
		}
		result.MorePaths = append(result.MorePaths, record.Path)
	}
}

func promoteGraphHead(
	ranked []RankedRecord,
	query string,
	excerptLimit int,
	lanes []lane,
	target int,
) []RankedRecord {
	var graph lane
	for _, candidate := range lanes {
		if candidate.name == "graph" && len(candidate.records) > 0 {
			graph = candidate
			break
		}
	}
	if len(graph.records) == 0 || target < 1 {
		return ranked
	}
	index := -1
	for candidate := range ranked {
		if ranked[candidate].Path == graph.records[0].Path {
			index = candidate
			break
		}
	}
	if index < 0 {
		head := fuse(query, 1, excerptLimit, []lane{graph})
		if len(head) == 0 {
			return ranked
		}
		ranked = append(ranked, head[0])
		index = len(ranked) - 1
	}
	if index <= target || target >= len(ranked) {
		return ranked
	}
	graphValue := ranked[index]
	displaced := ranked[target]
	ranked = append(ranked[:index], ranked[index+1:]...)
	ranked[target] = graphValue
	ranked = append(ranked, displaced)
	overflowEnd := min(len(ranked)-1, target+maxMorePaths)
	if last := len(ranked) - 1; last > overflowEnd {
		ranked[last], ranked[overflowEnd] = ranked[overflowEnd], ranked[last]
	}
	return ranked
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

func fuse(query string, limit, excerptLimit int, lanes []lane) []RankedRecord {
	values := map[int64]*fused{}
	pathEvidence := make(map[string]*fused)
	familyScores := make(map[string]map[string]float64)
	pathRetrieval := make(map[string]map[string]bool)
	for _, candidateLane := range lanes {
		family := retrievalFamily(candidateLane.name)
		if familyScores[family] == nil {
			familyScores[family] = make(map[string]float64)
		}
		seenPaths := make(map[string]bool)
		for rank, record := range candidateLane.records {
			score := 1.0 / (laneRRFK + float64(rank+1))
			if candidateLane.name == "extractor" {
				score *= extractorLaneWeight
			} else if candidateLane.name == "graph" {
				score *= graphFamilyWeight
			}
			// Search results are files, while retrieval lanes return file,
			// chunk, and extractor records. Count only the best record for a
			// path in each lane, then fuse evidence across lanes at that same
			// result identity.
			if !seenPaths[record.Path] {
				seenPaths[record.Path] = true
				familyScores[family][record.Path] += score
				if pathRetrieval[record.Path] == nil {
					pathRetrieval[record.Path] = make(map[string]bool)
				}
				pathRetrieval[record.Path][candidateLane.name] = true
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
	pathScores := fuseRetrievalFamilies(familyScores)
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
	sortFused(ordered)
	recordHeadPaths := make([]string, 0, recordHeadResults)
	seenRecordPaths := make(map[string]bool)
	for _, value := range ordered {
		if len(recordHeadPaths) == recordHeadResults {
			break
		}
		if seenRecordPaths[value.record.Path] ||
			(value.record.Kind == "file" && hasCitedRecord[value.record.Path]) {
			continue
		}
		seenRecordPaths[value.record.Path] = true
		recordHeadPaths = append(recordHeadPaths, value.record.Path)
	}
	for _, value := range ordered {
		value.score = pathScores[value.record.Path]
	}
	sortFused(ordered)
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
	preserveHeadPaths(eligible, recordHeadPaths)
	result := make([]RankedRecord, 0, min(limit, len(eligible)))
	for _, value := range eligible {
		if len(result) == limit {
			break
		}
		citation := citations[value.record.Path]
		if citation == nil {
			citation = value
		}
		retrievalSet := make(map[string]bool, len(pathRetrieval[value.record.Path]))
		for name := range pathRetrieval[value.record.Path] {
			retrievalSet[name] = true
		}
		retrieval := make([]string, 0, len(retrievalSet))
		for name := range retrievalSet {
			retrieval = append(retrieval, name)
		}
		sort.Strings(retrieval)
		startLine, endLine, title, citationExcerpt :=
			localizedCitation(citation.record, citation.evidence, excerptLimit)
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

func retrievalFamily(laneName string) string {
	switch laneName {
	case "semantic", "extractor", "graph":
		return laneName
	default:
		return "lexical"
	}
}

func fuseRetrievalFamilies(families map[string]map[string]float64) map[string]float64 {
	result := make(map[string]float64)
	for _, family := range []string{"lexical", "semantic", "extractor", "graph"} {
		scores := families[family]
		if len(scores) == 0 {
			continue
		}
		type pathScore struct {
			path  string
			score float64
		}
		ordered := make([]pathScore, 0, len(scores))
		for path, score := range scores {
			ordered = append(ordered, pathScore{path: path, score: score})
		}
		sort.Slice(ordered, func(i, j int) bool {
			if ordered[i].score != ordered[j].score {
				return ordered[i].score > ordered[j].score
			}
			return ordered[i].path < ordered[j].path
		})
		weight := 1.0
		if family == "extractor" {
			weight = extractorLaneWeight
		} else if family == "graph" {
			weight = graphFamilyWeight
		}
		for rank, value := range ordered {
			result[value.path] += weight / (familyRRFK + float64(rank+1))
		}
	}
	return result
}

func sortFused(values []*fused) {
	sort.Slice(values, func(i, j int) bool {
		if values[i].score != values[j].score {
			return values[i].score > values[j].score
		}
		if values[i].record.Path != values[j].record.Path {
			return values[i].record.Path < values[j].record.Path
		}
		if values[i].record.StartLine != values[j].record.StartLine {
			return values[i].record.StartLine < values[j].record.StartLine
		}
		return values[i].record.ID < values[j].record.ID
	})
}

func preserveHeadPaths(values []*fused, paths []string) {
	next := 0
	for _, path := range paths {
		for index := next; index < len(values); index++ {
			if values[index].record.Path != path {
				continue
			}
			value := values[index]
			copy(values[next+1:index+1], values[next:index])
			values[next] = value
			next++
			break
		}
	}
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
	terms := queryTerms(query)
	if !literalTermQuery(query, terms) {
		return -1
	}
	identifier := codeIdentifierTermQuery(query, terms)
	for index, line := range strings.Split(text, "\n") {
		if (!identifier && strings.Contains(line, query)) ||
			(identifier && containsIdentifierLiteral(line, query)) {
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
	excerptLimit int,
) (int, int, string, string) {
	lines := strings.Split(record.Text, "\n")
	if evidence.terms == 0 || evidence.line < 0 ||
		record.EndLine-record.StartLine+1 < len(lines) {
		return record.StartLine, record.EndLine, record.Title, excerpt(record.Text, excerptLimit)
	}
	start := max(0, evidence.line-2)
	text := excerpt(strings.Join(lines[start:], "\n"), excerptLimit)
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
	if len(strings.Fields(query)) != 1 || len(terms) == 0 {
		return false
	}
	return strings.ContainsAny(query, "-_.:/#<>=()\"'`") ||
		codeIdentifierTermQuery(query, terms)
}

func codeIdentifierTermQuery(query string, terms []string) bool {
	if len(terms) != 1 || terms[0] != query || utf8.RuneCountInString(query) < 2 {
		return false
	}
	for _, value := range query {
		if unicode.IsUpper(value) {
			return true
		}
	}
	return false
}

func identifierLiteralRecords(records []store.Record, literal string) []store.Record {
	result := records[:0]
	for _, record := range records {
		if containsIdentifierLiteral(record.Text, literal) {
			result = append(result, record)
		}
	}
	return result
}

func containsIdentifierLiteral(text, literal string) bool {
	for offset := 0; offset <= len(text)-len(literal); {
		index := strings.Index(text[offset:], literal)
		if index < 0 {
			return false
		}
		start := offset + index
		end := start + len(literal)
		before := start > 0 && identifierRune(lastRune(text[:start]))
		after := end < len(text) && identifierRune(firstRune(text[end:]))
		if !before && !after {
			return true
		}
		offset = start + len(literal)
	}
	return false
}

func firstRune(value string) rune {
	result, _ := utf8.DecodeRuneInString(value)
	return result
}

func lastRune(value string) rune {
	result, _ := utf8.DecodeLastRuneInString(value)
	return result
}

func identifierRune(value rune) bool {
	return value == '_' || value == '$' ||
		unicode.IsLetter(value) || unicode.IsNumber(value) || unicode.IsMark(value)
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

func resultExcerptLimit(resultLimit int) int {
	if resultLimit <= 0 {
		return maxExcerptRunes
	}
	return max(minExcerptRunes, min(maxExcerptRunes, targetExcerptRunes/resultLimit))
}

func excerpt(text string, limit int) string {
	text = strings.TrimSpace(text)
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
