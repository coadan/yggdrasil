package query_test

import (
	"encoding/json"
	"reflect"
	"testing"

	"github.com/coadan/yggdrasil/query"
)

func TestParseSeparatesLexicalAndSemanticIntent(t *testing.T) {
	tests := []struct {
		name       string
		pattern    string
		kind       string
		about      string
		wantText   string
		wantSource string
		wantTerms  []string
	}{
		{
			name: "plain", pattern: "API error envelope", kind: query.MatchText,
			wantText: "API error envelope", wantSource: "pattern",
			wantTerms: []string{"API", "error", "envelope"},
		},
		{
			name: "fixed with intent", pattern: "InlineDisclosure", kind: query.MatchFixed,
			about: "shared disclosure component", wantText: "shared disclosure component",
			wantSource: "about", wantTerms: []string{"InlineDisclosure"},
		},
		{
			name: "regexp literals", pattern: `push.*error[-_]envelope`, kind: query.MatchRegexp,
			wantText: "push error envelope", wantSource: "regexp-literals",
			wantTerms: []string{"push", "error", "envelope"},
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			plan, err := query.Parse(test.pattern, test.kind, test.about, "src/")
			if err != nil {
				t.Fatal(err)
			}
			if plan.Scope != "src" || plan.Semantic == nil ||
				plan.Semantic.Text != test.wantText || plan.Semantic.Source != test.wantSource ||
				!reflect.DeepEqual(plan.LexicalTerms(), test.wantTerms) {
				t.Fatalf("plan=%#v terms=%v", plan, plan.LexicalTerms())
			}
		})
	}
}

func TestParseRejectsInvalidRegexp(t *testing.T) {
	if _, err := query.Parse("[", query.MatchRegexp, "", ""); err == nil {
		t.Fatal("invalid regexp was accepted")
	}
}

func TestPlanRetainsDerivedEvidenceAfterJSONRoundTrip(t *testing.T) {
	plan, err := query.Parse(`push.*error[-_]envelope`, query.MatchRegexp, "", "src/")
	if err != nil {
		t.Fatal(err)
	}
	raw, err := json.Marshal(plan)
	if err != nil {
		t.Fatal(err)
	}
	var decoded query.Plan
	if err := json.Unmarshal(raw, &decoded); err != nil {
		t.Fatal(err)
	}
	if got, want := decoded.EvidenceText(), "push error envelope"; got != want {
		t.Fatalf("evidence=%q want %q", got, want)
	}
}
