package query

import (
	"strings"
	"testing"
)

func TestLocateEvidenceWindowAndTokenSemantics(t *testing.T) {
	cases := []struct {
		name, text, query string
		want              citationEvidence
	}{
		{"literal identifier", "func HTTPServer() {}", "HTTPServer", citationEvidence{terms: 1, line: 0, literal: true}},
		{"identifier components", "func NewHTTPServer() {}", "HTTPServer", citationEvidence{terms: 1, line: 0}},
		{"five line window", "a\nb\nc\nd\ne", "a e", citationEvidence{terms: 2, line: 2}},
		{"separate windows", "a\nb\nc\nd\ne\nf", "a f", citationEvidence{terms: 1, line: 0}},
		{"repeated query terms", "owner", "owner OWNER owner", citationEvidence{terms: 1, line: 0}},
		{"overlapping groups", "fact", "FactList fact", citationEvidence{terms: 2, line: 0}},
		{"unicode components", "ΔιανομήHTTP()\nμνήμη", "ΔιανομήHTTP μνήμη", citationEvidence{terms: 2, line: 0}},
		{"uncased uppercase component", "foo𝐀bar", "foo missing", citationEvidence{terms: 1, line: 0}},
		{"first source casing without components", "foobar fooBar", "foo missing", citationEvidence{line: -1}},
		{"first source casing with components", "fooBar foobar", "foo missing", citationEvidence{terms: 1, line: 0}},
		{"long query components", "fooBar", "a b c d e f g h foo bar", citationEvidence{terms: 2, line: 0}},
		{"overlapping aliases", "fooBar", "FooBar foo bar", citationEvidence{terms: 3, line: 0}},
		{"numeric boundary", "HTTP2Server", "http2 server", citationEvidence{terms: 2, line: 0}},
		{"empty source", "", "missing token", citationEvidence{line: -1}},
		{"punctuation", "anything", "??", citationEvidence{line: -1}},
		{"no evidence", "nothing present", "missing token", citationEvidence{line: -1}},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := locateEvidence(c.text, c.query); got != c.want {
				t.Fatalf("got=%#v want=%#v", got, c.want)
			}
		})
	}
}

var evidenceBenchmarkResult citationEvidence

func BenchmarkLocateEvidence(b *testing.B) {
	cases := []struct{ name, text, query string }{
		{"plain", "fallback marker\n" + strings.Repeat("padding value for bounded retrieval memory\n", 512), "fallback marker"},
		{"code", strings.Repeat("const fieldValue = unrelatedState;\n", 512) + "return responseEnvelope;\n", "response envelope"},
		{"unicode", strings.Repeat("ΚαταγραφήHTTP μνήμη πεδίο\n", 512), "διαδρομή μνήμη"},
	}
	for _, c := range cases {
		b.Run(c.name, func(b *testing.B) {
			b.ReportAllocs()
			b.SetBytes(int64(len(c.text)))
			for b.Loop() {
				evidenceBenchmarkResult = locateEvidence(c.text, c.query)
			}
		})
	}
}
