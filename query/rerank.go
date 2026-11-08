package query

import (
	"context"
	"fmt"
	"math"
	"sort"
	"strconv"

	rerankcontract "github.com/coadan/yggdrasil/rerank"
)

// rerankRRFK fuses the fused rank with the reranker's rank. A reranker alone
// lost recall on the history suites; combining both ranks kept it.
const rerankRRFK = 20.0

func rerankResult(ctx context.Context, result *Result, limit int, capability rerankcontract.Capability) {
	depth := capability.Depth
	if depth == 0 {
		depth = rerankcontract.DefaultDepth
	}
	depth = min(depth, len(result.ranked))
	if depth < 2 {
		return
	}
	maxChars := capability.MaxInputChars
	if maxChars == 0 {
		maxChars = rerankcontract.DefaultMaxInputChars
	}
	// The query shares the reranker's per-pair context with each document. A
	// query over the input limit overflows that context, and truncating it
	// reranked diff-shaped queries worse than the lexical order, so it is
	// skipped instead.
	if len([]rune(result.Query)) > maxChars {
		result.Rerank = &RerankStatus{State: "skipped", Error: "query exceeds rerank input limit"}
		return
	}
	inputs := make([]rerankcontract.Input, depth)
	for index, record := range result.ranked[:depth] {
		inputs[index] = rerankcontract.Input{
			ID:   strconv.Itoa(index),
			Text: truncateRunes(record.Path+"\n"+record.evidence, maxChars),
		}
	}
	scores, err := capability.Provider.Rerank(ctx, result.Query, inputs)
	var order []int
	if err == nil {
		order, err = rerankOrder(inputs, scores)
	}
	if err != nil {
		result.Rerank = &RerankStatus{State: "failed", Error: err.Error()}
		return
	}
	// Partial semantic coverage appends supplementary paths after the ranked
	// results; keep them behind the reordered list.
	ranked := make(map[string]bool, len(result.ranked))
	for _, record := range result.ranked {
		ranked[record.Path] = true
	}
	var supplementary []string
	for _, path := range result.MorePaths {
		if !ranked[path] {
			supplementary = append(supplementary, path)
		}
	}
	reordered := make([]RankedRecord, 0, len(result.ranked))
	for _, index := range order {
		reordered = append(reordered, result.ranked[index])
	}
	result.ranked = append(reordered, result.ranked[depth:]...)
	splitRanked(result, limit)
	for _, path := range supplementary {
		if len(result.MorePaths) == maxMorePaths {
			break
		}
		result.MorePaths = append(result.MorePaths, path)
	}
	result.Rerank = &RerankStatus{State: "applied", Candidates: depth}
}

// rerankOrder returns input positions ordered by reciprocal-rank fusion of
// their original position and their reranker score rank.
func rerankOrder(inputs []rerankcontract.Input, scores []rerankcontract.Score) ([]int, error) {
	if len(scores) != len(inputs) {
		return nil, fmt.Errorf("reranker returned %d scores, want %d", len(scores), len(inputs))
	}
	position := make(map[string]int, len(inputs))
	for index, input := range inputs {
		position[input.ID] = index
	}
	byInput := make([]float64, len(inputs))
	seen := make([]bool, len(inputs))
	for _, score := range scores {
		index, ok := position[score.ID]
		if !ok {
			return nil, fmt.Errorf("reranker returned unknown id %q", score.ID)
		}
		if seen[index] {
			return nil, fmt.Errorf("reranker returned duplicate id %q", score.ID)
		}
		if math.IsNaN(score.Score) || math.IsInf(score.Score, 0) {
			return nil, fmt.Errorf("reranker returned non-finite score for %q", score.ID)
		}
		seen[index] = true
		byInput[index] = score.Score
	}
	byScore := make([]int, len(inputs))
	for index := range byScore {
		byScore[index] = index
	}
	sort.SliceStable(byScore, func(i, j int) bool { return byInput[byScore[i]] > byInput[byScore[j]] })
	fused := make([]float64, len(inputs))
	for rank, index := range byScore {
		fused[index] = 1/(rerankRRFK+float64(index+1)) + 1/(rerankRRFK+float64(rank+1))
	}
	order := make([]int, len(inputs))
	for index := range order {
		order[index] = index
	}
	sort.SliceStable(order, func(i, j int) bool { return fused[order[i]] > fused[order[j]] })
	return order, nil
}

func truncateRunes(value string, limit int) string {
	count := 0
	for index := range value {
		if count == limit {
			return value[:index]
		}
		count++
	}
	return value
}
