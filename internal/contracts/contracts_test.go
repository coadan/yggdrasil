package contracts

import (
	"encoding/json"
	"testing"
)

func TestSearchRecordJSONUsesCanonicalFieldNames(t *testing.T) {
	encoded, err := json.Marshal(SearchRecord{
		Path:      "README.md",
		StartLine: 1,
		EndLine:   2,
		Kind:      "text",
		Text:      "hello",
	})
	if err != nil {
		t.Fatal(err)
	}
	const want = `{"path":"README.md","startLine":1,"endLine":2,"kind":"text","text":"hello"}`
	if string(encoded) != want {
		t.Fatalf("got %s, want %s", encoded, want)
	}
}
