package query

import (
	"encoding/json"
	"fmt"
)

// SemanticReadiness describes the immutable vector-coverage snapshot observed
// by one query. It is evidence about an eventually consistent derived index.
type SemanticReadiness struct {
	State              string  `json:"state"`
	Embedded           int     `json:"embedded"`
	Records            int     `json:"records"`
	Coverage           float64 `json:"coverage"`
	ActivationCoverage float64 `json:"activationCoverage"`
}

type Result struct {
	Schema         string             `json:"schema"`
	Query          string             `json:"query"`
	RequestedMode  string             `json:"requestedMode"`
	ActiveMode     string             `json:"activeMode"`
	FallbackReason string             `json:"fallbackReason,omitempty"`
	ElapsedMS      int64              `json:"elapsedMs"`
	QueryPlan      Plan               `json:"queryPlan"`
	Records        []RankedRecord     `json:"records"`
	MorePaths      []string           `json:"morePaths,omitempty"`
	Semantic       *SemanticReadiness `json:"semantic,omitempty"`
}

type RankedRecord struct {
	Path      string         `json:"path"`
	StartLine int            `json:"startLine"`
	EndLine   int            `json:"endLine"`
	Kind      string         `json:"kind,omitempty"`
	Title     string         `json:"title,omitempty"`
	Excerpt   string         `json:"excerpt"`
	Metadata  map[string]any `json:"metadata,omitempty"`
	Source    string         `json:"source,omitempty"`
	// Retrieval and Score remain available to in-process ranking tests and
	// diagnostics but are deliberately omitted from the stable JSON contract.
	Retrieval []string `json:"-"`
	Score     float64  `json:"-"`
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
