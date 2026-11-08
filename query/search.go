// Package query ranks bounded records from an immutable repository snapshot.
package query

import (
	"context"
	"errors"
	"fmt"
	"regexp"
	"sort"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	embeddingcontract "github.com/coadan/yggdrasil/embedding"
	"github.com/coadan/yggdrasil/internal/contracts"
	rerankcontract "github.com/coadan/yggdrasil/rerank"
)

const laneRRFK = 60.0
const familyRRFK = 10.0
const maxPathTerms = 6
const maxStructuredAnchors = 4
const maxEvidencePrefilterTerms = 8
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
	MaxQueryBytes = MaxBytes
	MaxQueryTerms = MaxTerms
)

var ErrSemanticUnavailable = errors.New("semantic search is unavailable")

type Options struct {
	Mode          string
	Limit         int
	Scope         string
	MatchKind     string
	About         string
	HasExtractors bool
	Embedding     *embeddingcontract.Capability
	// Rerank rescores the leading fused results of natural-language text
	// queries. Nil leaves fused order unchanged.
	Rerank *rerankcontract.Capability
	// MinSemanticCoverage activates semantic work after this fraction of the
	// requested scope has compatible vectors. Zero preserves the complete-only
	// policy. Values must otherwise be in (0,1].
	MinSemanticCoverage float64
}

func Run(ctx context.Context, value Reader, query string, opts Options) (Result, error) {
	started := time.Now()
	if opts.Rerank != nil && (opts.Rerank.Provider == nil ||
		opts.Rerank.Depth < 0 || opts.Rerank.MaxInputChars < 0) {
		return Result{}, errors.New("rerank capability requires a provider and non-negative bounds")
	}
	result, err := run(ctx, value, query, opts)
	if err != nil || opts.Rerank == nil || !result.rerankable {
		return result, err
	}
	limit := opts.Limit
	if limit == 0 {
		limit = 10
	}
	rerankResult(ctx, &result, limit, *opts.Rerank)
	result.ElapsedMS = time.Since(started).Milliseconds()
	return result, nil
}

func run(ctx context.Context, value Reader, query string, opts Options) (Result, error) {
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
	plan, err := Parse(query, opts.MatchKind, opts.About, opts.Scope)
	if err != nil {
		return Result{}, fmt.Errorf("plan search query: %w", err)
	}
	evidenceQuery := plan.EvidenceText()
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
	activationCoverage := opts.MinSemanticCoverage
	if activationCoverage == 0 {
		activationCoverage = 1
	}
	if activationCoverage <= 0 || activationCoverage > 1 {
		return Result{}, errors.New("minimum semantic coverage must be greater than zero and at most one")
	}
	candidateLimit := max(100, min(MaxResults+maxMorePaths, opts.Limit*10))
	var lanes []lane
	structured := false
	// kind is the lexical lane actually used; a punctuation-only text query
	// runs as a fixed string and must never reach FTS.
	kind := plan.Lexical.Kind
	if opts.Mode != "semantic" {
		terms := queryTerms(query)
		literal := plan.Lexical.Kind == MatchText && literalTermQuery(query, terms)
		structured = literal
		if kind == MatchText && len(terms) == 0 {
			// Without letter or digit terms, FTS has nothing to tokenize. The
			// query text can still occur verbatim, as in ???, -> or ::.
			kind = MatchFixed
			structured = true
		}
		switch kind {
		case MatchFixed:
			records, err := value.LiteralCandidates(
				ctx, "", query, opts.Scope, candidateLimit,
			)
			if err != nil {
				return Result{}, fmt.Errorf("fixed search: %w", err)
			}
			lanes = append(lanes, literalCandidateLane("fixed", records, query, false))
		case MatchRegexp:
			matches, err := regexpCandidates(ctx, value, plan, opts.Scope, candidateLimit)
			if err != nil {
				return Result{}, fmt.Errorf("regexp search: %w", err)
			}
			lanes = append(lanes, matches)
		case MatchText:
			if literal {
				records, err := value.LiteralCandidates(
					ctx, ftsAnyQuery(terms), query, opts.Scope, candidateLimit,
				)
				if err != nil {
					return Result{}, fmt.Errorf("literal search: %w", err)
				}
				identifier := codeIdentifierTermQuery(query, terms)
				lanes = append(lanes, literalCandidateLane("literal", records, query, identifier))
				if opts.HasExtractors {
					// Extractor records stay out of the core lexical index, so
					// parser-owned declarations need their own literal lane.
					extracted, err := value.ExtractorCandidates(
						ctx, ftsAnyQuery(terms), opts.Scope, candidateLimit,
					)
					if err != nil {
						return Result{}, fmt.Errorf("extractor literal search: %w", err)
					}
					lanes = append(lanes, literalCandidateLane("extractor-literal", extracted, query, identifier))
				}
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
		}
		if kind == MatchText && !structured {
			fts, err := value.LexicalCandidates(
				ctx, ftsAnyQuery(terms), opts.Scope, candidateLimit,
			)
			if err != nil {
				return Result{}, fmt.Errorf("lexical search: %w", err)
			}
			stemmed, err := value.StemmedCandidates(
				ctx, ftsAnyQuery(terms), opts.Scope, candidateLimit,
			)
			if err != nil {
				return Result{}, fmt.Errorf("stemmed lexical search: %w", err)
			}
			paths, err := value.PathCandidates(
				ctx, pathTerms(query), opts.Scope, candidateLimit,
			)
			if err != nil {
				return Result{}, fmt.Errorf("path search: %w", err)
			}
			lanes = append(lanes,
				lane{name: "lexical", records: fts},
				lane{name: "stemmed", records: stemmed},
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
		QueryPlan:     plan,
		RequestedMode: opts.Mode,
		ActiveMode:    "lexical",
		rerankable:    kind == MatchText && !structured && opts.Mode != "graph",
	}
	if opts.Mode == "lexical" {
		setResultRecords(&result, evidenceQuery, opts.Limit, lanes)
		result.ElapsedMS = time.Since(started).Milliseconds()
		return result, nil
	}
	if opts.Mode == "graph" {
		graph, err := graphLane(ctx, value, evidenceQuery, lanes, opts.Scope, candidateLimit)
		if err != nil {
			return Result{}, err
		}
		result.ActiveMode = "graph"
		setResultRecords(&result, evidenceQuery, opts.Limit, []lane{graph})
		result.ElapsedMS = time.Since(started).Milliseconds()
		return result, nil
	}
	// Structured queries keep their exact lanes and expand them through the
	// graph. In auto mode they return without semantic retrieval unless
	// --about states semantic intent; any semantic fallback keeps the graph.
	graphAdded := false
	if structured && opts.Mode == "auto" {
		if opts.HasExtractors {
			graph, graphErr := graphLane(ctx, value, evidenceQuery, lanes, opts.Scope, candidateLimit)
			if graphErr != nil {
				return Result{}, graphErr
			}
			graphAdded = true
			if len(graph.records) > 0 {
				lanes = append(lanes, graph)
				result.ActiveMode = "hybrid"
			}
		}
		if plan.Semantic == nil || plan.Semantic.Source != "about" {
			setResultRecords(&result, evidenceQuery, opts.Limit, lanes)
			result.ElapsedMS = time.Since(started).Milliseconds()
			return result, nil
		}
	}
	if plan.Semantic == nil {
		if opts.Mode == "semantic" {
			return Result{}, fmt.Errorf("%w: regexp has no semantic text; use --about", ErrSemanticUnavailable)
		}
		result.FallbackReason = "semantic-query-empty"
		setResultRecords(&result, evidenceQuery, opts.Limit, lanes)
		result.ElapsedMS = time.Since(started).Milliseconds()
		return result, nil
	}
	if opts.Embedding == nil {
		if opts.Mode == "semantic" {
			return Result{}, fmt.Errorf("%w: no embedding provider is configured", ErrSemanticUnavailable)
		}
		if opts.Mode == "auto" {
			result.FallbackReason = "semantic-unconfigured"
			result.Semantic = &SemanticReadiness{State: "unconfigured", ActivationCoverage: activationCoverage}
		}
		setResultRecords(&result, evidenceQuery, opts.Limit, lanes)
		result.ElapsedMS = time.Since(started).Milliseconds()
		return result, nil
	}
	fingerprint := embeddingcontract.Fingerprint(*opts.Embedding)
	state, err := value.EmbeddingStateForScope(ctx, fingerprint, opts.Scope)
	if err != nil {
		return Result{}, fmt.Errorf("inspect embedding lane: %w", err)
	}
	coverage := 0.0
	if state.Records > 0 {
		coverage = float64(state.Embedded) / float64(state.Records)
	}
	result.Semantic = &SemanticReadiness{
		State: "warming", Embedded: state.Embedded, Records: state.Records,
		Coverage: coverage, ActivationCoverage: activationCoverage,
	}
	active := state.Records > 0 && coverage >= activationCoverage
	if !active {
		if opts.Mode == "semantic" {
			return Result{}, fmt.Errorf(
				"%w: index has %d of %d current vectors",
				ErrSemanticUnavailable, state.Embedded, state.Records,
			)
		}
		result.FallbackReason = "semantic-below-threshold"
		setResultRecords(&result, evidenceQuery, opts.Limit, lanes)
		result.ElapsedMS = time.Since(started).Milliseconds()
		return result, nil
	}
	provider := opts.Embedding.Provider
	if provider == nil {
		return semanticFailure(result, opts, lanes, started, errors.New("embedding provider is unavailable"))
	}
	values, embedErr := provider.Embed(ctx, []embeddingcontract.Input{{
		ID: "query", Text: opts.Embedding.QueryPrefix + plan.Semantic.Text,
	}})
	if embedErr != nil {
		return semanticFailure(result, opts, lanes, started, embedErr)
	}
	if len(values) != 1 || values[0].ID != "query" {
		return semanticFailure(result, opts, lanes, started, errors.New("provider returned an invalid query embedding"))
	}
	vectors, err := value.VectorCandidates(ctx, values[0].Vector, opts.Scope, candidateLimit)
	if err != nil {
		return Result{}, fmt.Errorf("semantic search: %w", err)
	}
	semanticLane := lane{name: "semantic", records: vectors}
	lanes = append(lanes, semanticLane)
	result.Semantic.State = "ready"
	if !state.Complete {
		result.Semantic.State = "active-partial"
	}
	if opts.Mode == "semantic" {
		result.ActiveMode = "semantic"
	} else {
		if opts.HasExtractors && !graphAdded {
			graph, graphErr := graphLane(ctx, value, evidenceQuery, lanes, opts.Scope, candidateLimit)
			if graphErr != nil {
				return Result{}, graphErr
			}
			lanes = append(lanes, graph)
		}
		if state.Complete {
			result.ActiveMode = "hybrid"
		} else {
			// Partial semantic coverage can surface additional paths, but it must
			// not displace the concrete lexical and structural result budget.
			concrete := make([]lane, 0, len(lanes)-1)
			for _, candidate := range lanes {
				if candidate.name != "semantic" {
					concrete = append(concrete, candidate)
				}
			}
			setResultRecords(&result, evidenceQuery, opts.Limit, concrete)
			appendSupplementaryPaths(&result, semanticLane.records)
			result.FallbackReason = "semantic-partial"
			result.ElapsedMS = time.Since(started).Milliseconds()
			return result, nil
		}
	}
	setResultRecords(&result, evidenceQuery, opts.Limit, lanes)
	result.ElapsedMS = time.Since(started).Milliseconds()
	return result, nil
}

func appendSupplementaryPaths(result *Result, records []Candidate) {
	seen := make(map[string]bool, len(result.Records)+len(result.MorePaths))
	for _, record := range result.Records {
		seen[record.Path] = true
	}
	for _, path := range result.MorePaths {
		seen[path] = true
	}
	for _, record := range records {
		if seen[record.Path] {
			continue
		}
		result.MorePaths = append(result.MorePaths, record.Path)
		seen[record.Path] = true
		if len(result.MorePaths) == maxMorePaths {
			return
		}
	}
}

func graphLane(
	ctx context.Context,
	value Reader,
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

func regexpCandidates(
	ctx context.Context,
	value Reader,
	plan Plan,
	scope string,
	limit int,
) (lane, error) {
	expression, err := regexp.Compile(plan.Lexical.Pattern)
	if err != nil {
		return lane{}, err
	}
	// FTS token boundaries are not a safe prefilter for a regular expression:
	// a literal such as "push" can match inside pushCommand while unicode61
	// indexes the latter as one token. Scan indexed lexical records so regexp
	// matching retains grep's no-false-negative guarantee.
	records, err := value.LexicalRecords(ctx, "", scope)
	if err != nil {
		return lane{}, err
	}
	result := lane{
		name: "regexp", records: make([]Candidate, 0, min(limit, len(records))),
		matches: make(map[int64]matchSpan),
	}
	seenPaths := make(map[string]bool)
	for _, record := range records {
		if record.Kind == "file" {
			continue
		}
		match := expression.FindStringIndex(record.Text)
		if match == nil {
			continue
		}
		result.records = append(result.records, record)
		result.matches[record.ID] = matchSpan{start: match[0], end: match[1]}
		seenPaths[record.Path] = true
		if len(seenPaths) == limit {
			break
		}
	}
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
	setResultRecords(&result, result.QueryPlan.EvidenceText(), opts.Limit, lanes)
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
	result.ranked = ranked
	splitRanked(result, limit)
}

func splitRanked(result *Result, limit int) {
	ranked := result.ranked
	result.MorePaths = nil
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
	records []Candidate
	matches map[int64]matchSpan
}

type matchSpan struct{ start, end int }

type fused struct {
	record    Candidate
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
			if match, ok := candidateLane.matches[record.ID]; ok {
				value.evidence = (citationEvidence{literal: true}).atMatch(record.Text, match)
			}
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
		if !value.evidence.literal {
			value.evidence = locateEvidence(value.record.Text, query)
		}
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
			Path:      citation.record.Path,
			StartLine: startLine,
			EndLine:   endLine,
			Kind:      citation.record.Kind,
			Title:     title,
			Excerpt:   citationExcerpt,
			Metadata:  citation.record.Metadata,
			Source:    citation.record.Source,
			Retrieval: retrieval,
			Score:     value.score,
			evidence:  citationText(citation.record, citation.evidence),
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
	match   matchSpan
}

func (e citationEvidence) atMatch(text string, match matchSpan) citationEvidence {
	e.terms = max(1, e.terms)
	e.line = strings.Count(text[:match.start], "\n")
	e.match = match
	return e
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
	for index, name := range []string{"fixed", "regexp", "literal", "exact", "all-terms", "anchor", "extractor", "lexical"} {
		if retrieval[name] {
			return 8 - index
		}
	}
	return 0
}

func locateEvidence(text, query string) citationEvidence {
	if line := literalEvidenceLine(text, query); line >= 0 {
		return citationEvidence{terms: 1, line: line, literal: true}
	}
	groups := evidenceTermGroups(query)
	if len(groups) == 0 {
		return citationEvidence{line: -1}
	}
	type termState struct {
		groups   []int
		lines    int
		lastLine int
	}
	wanted := make(map[string]*termState)
	for groupID, group := range groups {
		for _, term := range group {
			state := wanted[term]
			if state == nil {
				state = &termState{}
				wanted[term] = state
			}
			state.groups = append(state.groups, groupID)
		}
	}
	// Count groups through active terms in the five-line window. Repeated
	// evidence does not rescan every query group at every source position.
	var lineTerms [5][]*termState
	groupTerms := make([]int, len(groups))
	activeGroups := 0
	seenTokens := make(map[string]bool)
	best := citationEvidence{line: -1}
	count := 0
	for sourceLine := range strings.SplitSeq(text, "\n") {
		slot := count % len(lineTerms)
		for _, state := range lineTerms[slot] {
			state.lines--
			if state.lines == 0 {
				for _, groupID := range state.groups {
					groupTerms[groupID]--
					if groupTerms[groupID] == 0 {
						activeGroups--
					}
				}
			}
		}
		lineTerms[slot] = lineTerms[slot][:0]
		clear(seenTokens)
		add := func(term string) {
			state := wanted[term]
			if state == nil || state.lastLine == count+1 {
				return
			}
			state.lastLine = count + 1
			lineTerms[slot] = append(lineTerms[slot], state)
			if state.lines == 0 {
				for _, groupID := range state.groups {
					if groupTerms[groupID] == 0 {
						activeGroups++
					}
					groupTerms[groupID]++
				}
			}
			state.lines++
		}
		for token := range strings.FieldsFuncSeq(sourceLine, func(r rune) bool {
			return !unicode.IsLetter(r) && !unicode.IsDigit(r)
		}) {
			lower := strings.ToLower(token)
			if seenTokens[lower] {
				continue
			}
			seenTokens[lower] = true
			add(lower)
			// A case-delimited component must occur inside the folded full
			// token. Bound this prefilter to short queries; longer queries
			// use the regular tokenizer without a term-by-token product.
			if len(wanted) > maxEvidencePrefilterTerms {
				visitEvidenceTokenParts(token, add)
			} else {
				for term := range wanted {
					if len(term) < len(lower) && strings.Contains(lower, term) {
						visitEvidenceTokenParts(token, add)
						break
					}
				}
			}
			if activeGroups == len(groups) {
				return citationEvidence{terms: activeGroups, line: max(0, count-2)}
			}
		}
		count++
		if count >= 3 && activeGroups > best.terms {
			best = citationEvidence{terms: activeGroups, line: count - 3}
		}
	}
	if count < 3 && activeGroups > 0 {
		best = citationEvidence{terms: activeGroups, line: 0}
	}
	// The final two windows only remove lines; neither can improve the
	// maximum score or its earliest occurrence.
	return best
}

func literalEvidenceLine(text, query string) int {
	terms := queryTerms(query)
	if !literalTermQuery(query, terms) {
		return -1
	}
	identifier := codeIdentifierTermQuery(query, terms)
	index := 0
	for line := range strings.SplitSeq(text, "\n") {
		if (!identifier && strings.Contains(line, query)) ||
			(identifier && containsIdentifierLiteral(line, query)) {
			return index
		}
		index++
	}
	return -1
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
	if token == "" {
		return nil
	}
	lower := strings.ToLower(token)
	seen := map[string]bool{lower: true}
	result := []string{lower}
	visitEvidenceTokenParts(token, func(term string) {
		if !seen[term] {
			seen[term] = true
			result = append(result, term)
		}
	})
	return result
}

// visitEvidenceTokenParts emits case-delimited components in addition to the
// full token handled by the caller. Source callers filter components as they
// arrive; query callers collect distinct aliases.
func visitEvidenceTokenParts(token string, visit func(string)) {
	if !strings.ContainsFunc(token, unicode.IsUpper) {
		return
	}
	runes := []rune(token)
	start := 0
	for index := 1; index < len(runes); index++ {
		boundary := unicode.IsUpper(runes[index]) &&
			(unicode.IsLower(runes[index-1]) ||
				(index+1 < len(runes) && unicode.IsLower(runes[index+1])))
		if !boundary {
			continue
		}
		visit(strings.ToLower(string(runes[start:index])))
		start = index
	}
	if start > 0 {
		visit(strings.ToLower(string(runes[start:])))
	}
}

func localizedCitation(
	record Candidate,
	evidence citationEvidence,
	excerptLimit int,
) (int, int, string, string) {
	if evidence.terms == 0 || evidence.line < 0 ||
		record.EndLine-record.StartLine+1 < strings.Count(record.Text, "\n")+1 {
		return record.StartLine, record.EndLine, record.Title, strings.Clone(excerpt(record.Text, excerptLimit))
	}
	start, offset := citationOffset(record, evidence)
	// Prefer context around an exact match when it fits. A long prefix can
	// consume the excerpt budget before the matched text is reached.
	if evidence.match.end > 0 &&
		utf8.RuneCountInString(record.Text[offset:evidence.match.end]) > excerptLimit {
		start, offset = evidence.line, evidence.match.start
	}
	// excerpt trims leading blank lines; the cited start must skip them too.
	rest := record.Text[offset:]
	start += strings.Count(rest[:len(rest)-len(strings.TrimLeftFunc(rest, unicode.IsSpace))], "\n")
	text := strings.Clone(excerpt(rest, excerptLimit))
	startLine := record.StartLine + start
	endLine := min(record.EndLine, startLine+strings.Count(text, "\n"))
	title := record.Title
	if title == fmt.Sprintf("%s:%d", record.Path, record.StartLine) {
		title = fmt.Sprintf("%s:%d", record.Path, startLine)
	}
	return startLine, endLine, title, text
}

// citationOffset returns the line and byte offset where a localized citation
// begins: two lines before the first evidence line.
func citationOffset(record Candidate, evidence citationEvidence) (int, int) {
	start := max(0, evidence.line-2)
	offset := 0
	for index, skipped := 0, 0; index < len(record.Text) && skipped < start; index++ {
		if record.Text[index] == '\n' {
			skipped++
			offset = index + 1
		}
	}
	return start, offset
}

// citationText is the record text from the localized citation onward, the
// span a reranker scores for this result.
func citationText(record Candidate, evidence citationEvidence) string {
	if evidence.terms == 0 || evidence.line < 0 {
		return record.Text
	}
	_, offset := citationOffset(record, evidence)
	return record.Text[offset:]
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

func literalCandidateLane(name string, records []Candidate, literal string, identifier bool) lane {
	result := lane{
		name: name, records: make([]Candidate, 0, len(records)),
		matches: make(map[int64]matchSpan, len(records)),
	}
	for _, record := range records {
		start := literalOffset(record.Text, literal, identifier)
		if start < 0 {
			continue
		}
		result.records = append(result.records, record)
		result.matches[record.ID] = matchSpan{start: start, end: start + len(literal)}
	}
	return result
}

func containsIdentifierLiteral(text, literal string) bool {
	return literalOffset(text, literal, true) >= 0
}

func literalOffset(text, literal string, identifier bool) int {
	if !identifier {
		return strings.Index(text, literal)
	}
	for offset := 0; offset <= len(text)-len(literal); {
		index := strings.Index(text[offset:], literal)
		if index < 0 {
			return -1
		}
		start := offset + index
		end := start + len(literal)
		before := start > 0 && identifierRune(lastRune(text[:start]))
		after := end < len(text) && identifierRune(firstRune(text[end:]))
		if !before && !after {
			return start
		}
		offset = start + len(literal)
	}
	return -1
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
