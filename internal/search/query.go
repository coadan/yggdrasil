package search

import (
	"errors"
	"regexp"
	"regexp/syntax"
	"strings"
	"unicode"
	"unicode/utf8"
)

const (
	MatchText   = "text"
	MatchFixed  = "fixed"
	MatchRegexp = "regexp"
)

type QueryPlan struct {
	Lexical  LexicalQuery   `json:"lexical"`
	Semantic *SemanticQuery `json:"semantic,omitempty"`
	Scope    string         `json:"scope,omitempty"`

	lexicalTerms []string
}

type LexicalQuery struct {
	Kind    string `json:"kind"`
	Pattern string `json:"pattern"`
}

type SemanticQuery struct {
	Text   string `json:"text"`
	Source string `json:"source"`
}

func PlanQuery(pattern, matchKind, about, scope string) (QueryPlan, error) {
	pattern = strings.TrimSpace(pattern)
	if pattern == "" {
		return QueryPlan{}, errors.New("search query is required")
	}
	if !utf8.ValidString(pattern) {
		return QueryPlan{}, errors.New("search query must be valid UTF-8")
	}
	if len(pattern) > MaxQueryBytes {
		return QueryPlan{}, errors.New("search query exceeds the byte limit")
	}
	if len(strings.Fields(pattern)) > MaxQueryTerms {
		return QueryPlan{}, errors.New("search query exceeds the term limit")
	}
	if matchKind == "" {
		matchKind = MatchText
	}
	plan := QueryPlan{
		Lexical: LexicalQuery{Kind: matchKind, Pattern: pattern},
		Scope:   strings.TrimSuffix(scope, "/"),
	}
	semanticText := strings.TrimSpace(about)
	if !utf8.ValidString(semanticText) || len(semanticText) > MaxQueryBytes ||
		len(strings.Fields(semanticText)) > MaxQueryTerms {
		return QueryPlan{}, errors.New("semantic intent exceeds search query limits")
	}
	semanticSource := "about"
	switch matchKind {
	case MatchText, MatchFixed:
		plan.lexicalTerms = queryTerms(pattern)
		if semanticText == "" {
			semanticText = pattern
			semanticSource = "pattern"
		}
	case MatchRegexp:
		if _, err := regexp.Compile(pattern); err != nil {
			return QueryPlan{}, err
		}
		plan.lexicalTerms = regexpLiteralTerms(pattern)
		if semanticText == "" {
			semanticText = strings.Join(plan.lexicalTerms, " ")
			semanticSource = "regexp-literals"
		}
	default:
		return QueryPlan{}, errors.New("search match kind must be text, fixed, or regexp")
	}
	if semanticText != "" {
		plan.Semantic = &SemanticQuery{Text: semanticText, Source: semanticSource}
	}
	return plan, nil
}

func (p QueryPlan) evidenceText() string {
	values := make([]string, 0, 2)
	if p.Lexical.Kind == MatchRegexp {
		values = append(values, strings.Join(p.lexicalTerms, " "))
	} else {
		values = append(values, p.Lexical.Pattern)
	}
	if p.Semantic != nil && p.Semantic.Source == "about" {
		values = append(values, p.Semantic.Text)
	}
	return strings.TrimSpace(strings.Join(values, " "))
}

func regexpLiteralTerms(pattern string) []string {
	expression, err := syntax.Parse(pattern, syntax.Perl)
	if err != nil {
		return nil
	}
	seen := make(map[string]bool)
	var result []string
	var visit func(*syntax.Regexp)
	visit = func(value *syntax.Regexp) {
		if value.Op == syntax.OpLiteral {
			literal := string(value.Rune)
			for _, term := range queryTerms(literal) {
				term = strings.TrimFunc(term, func(r rune) bool {
					return !unicode.IsLetter(r) && !unicode.IsDigit(r)
				})
				key := strings.ToLower(term)
				if key == "" || seen[key] {
					continue
				}
				seen[key] = true
				result = append(result, term)
			}
		}
		for _, child := range value.Sub {
			visit(child)
		}
	}
	visit(expression)
	return result
}
