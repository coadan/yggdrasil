package search

import (
	"reflect"
	"testing"
)

func TestPlanQuerySeparatesLexicalAndSemanticIntent(t *testing.T) {
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
			name: "plain", pattern: "API error envelope", kind: MatchText,
			wantText: "API error envelope", wantSource: "pattern",
			wantTerms: []string{"API", "error", "envelope"},
		},
		{
			name: "fixed with intent", pattern: "InlineDisclosure", kind: MatchFixed,
			about: "shared disclosure component", wantText: "shared disclosure component",
			wantSource: "about", wantTerms: []string{"InlineDisclosure"},
		},
		{
			name: "regexp literals", pattern: `push.*error[-_]envelope`, kind: MatchRegexp,
			wantText: "push error envelope", wantSource: "regexp-literals",
			wantTerms: []string{"push", "error", "envelope"},
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			plan, err := PlanQuery(test.pattern, test.kind, test.about, "src/")
			if err != nil {
				t.Fatal(err)
			}
			if plan.Scope != "src" || plan.Semantic == nil ||
				plan.Semantic.Text != test.wantText || plan.Semantic.Source != test.wantSource ||
				!reflect.DeepEqual(plan.lexicalTerms, test.wantTerms) {
				t.Fatalf("plan=%#v", plan)
			}
		})
	}
}

func TestPlanQueryRejectsInvalidRegexp(t *testing.T) {
	if _, err := PlanQuery("[", MatchRegexp, "", ""); err == nil {
		t.Fatal("invalid regexp was accepted")
	}
}
