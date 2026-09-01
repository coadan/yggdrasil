// Package query owns validated repository-search query plans.
//
// Planning is pure: it does not open an index, inspect a repository, load
// configuration, or start optional providers.
package query

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

	MaxBytes = 16 * 1024
	MaxTerms = 256
)

type Plan struct {
	Lexical  Lexical   `json:"lexical"`
	Semantic *Semantic `json:"semantic,omitempty"`
	Scope    string    `json:"scope,omitempty"`
}

type Lexical struct {
	Kind    string `json:"kind"`
	Pattern string `json:"pattern"`
}

type Semantic struct {
	Text   string `json:"text"`
	Source string `json:"source"`
}

func Parse(pattern, matchKind, about, scope string) (Plan, error) {
	pattern = strings.TrimSpace(pattern)
	if pattern == "" {
		return Plan{}, errors.New("search query is required")
	}
	if !utf8.ValidString(pattern) {
		return Plan{}, errors.New("search query must be valid UTF-8")
	}
	if len(pattern) > MaxBytes {
		return Plan{}, errors.New("search query exceeds the byte limit")
	}
	if len(strings.Fields(pattern)) > MaxTerms {
		return Plan{}, errors.New("search query exceeds the term limit")
	}
	if matchKind == "" {
		matchKind = MatchText
	}
	plan := Plan{
		Lexical: Lexical{Kind: matchKind, Pattern: pattern},
		Scope:   strings.TrimSuffix(scope, "/"),
	}
	semanticText := strings.TrimSpace(about)
	if !utf8.ValidString(semanticText) || len(semanticText) > MaxBytes ||
		len(strings.Fields(semanticText)) > MaxTerms {
		return Plan{}, errors.New("semantic intent exceeds search query limits")
	}
	semanticSource := "about"
	switch matchKind {
	case MatchText, MatchFixed:
		if semanticText == "" {
			semanticText = pattern
			semanticSource = "pattern"
		}
	case MatchRegexp:
		if _, err := regexp.Compile(pattern); err != nil {
			return Plan{}, err
		}
		if semanticText == "" {
			semanticText = strings.Join(regexpLiteralTerms(pattern), " ")
			semanticSource = "regexp-literals"
		}
	default:
		return Plan{}, errors.New("search match kind must be text, fixed, or regexp")
	}
	if semanticText != "" {
		plan.Semantic = &Semantic{Text: semanticText, Source: semanticSource}
	}
	return plan, nil
}

// EvidenceText returns the concrete text used to locate and bound citations.
func (p Plan) EvidenceText() string {
	values := make([]string, 0, 2)
	if p.Lexical.Kind == MatchRegexp {
		values = append(values, strings.Join(p.LexicalTerms(), " "))
	} else {
		values = append(values, p.Lexical.Pattern)
	}
	if p.Semantic != nil && p.Semantic.Source == "about" {
		values = append(values, p.Semantic.Text)
	}
	return strings.TrimSpace(strings.Join(values, " "))
}

// LexicalTerms returns a copy of the mechanically extracted lexical terms.
func (p Plan) LexicalTerms() []string {
	if p.Lexical.Kind == MatchRegexp {
		return regexpLiteralTerms(p.Lexical.Pattern)
	}
	return terms(p.Lexical.Pattern)
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
			for _, term := range terms(literal) {
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

func terms(value string) []string {
	return strings.FieldsFunc(value, func(r rune) bool {
		return !unicode.IsLetter(r) && !unicode.IsDigit(r)
	})
}
