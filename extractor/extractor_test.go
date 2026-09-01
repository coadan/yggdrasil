package extractor

import (
	"strings"
	"testing"
)

func TestNormalizeRecordsOwnsPathAndSource(t *testing.T) {
	records, err := NormalizeRecords(
		Descriptor{ID: "go", Fingerprint: "v1"},
		File{Path: "src/main.go", Content: "package main\nfunc main() {}\n"},
		[]Record{{ID: "main", StartLine: 2, EndLine: 2, Kind: "symbol", Text: "main function"}},
	)
	if err != nil {
		t.Fatal(err)
	}
	if len(records) != 1 || records[0].Path != "src/main.go" || records[0].Source != "plugin:go" {
		t.Fatalf("records=%#v", records)
	}
}

func TestNormalizeRecordsRejectsCrossFileAndDuplicateFacts(t *testing.T) {
	descriptor := Descriptor{ID: "go", Fingerprint: "v1"}
	file := File{Path: "src/main.go", Content: "one\ntwo\n"}
	for _, test := range []struct {
		name    string
		records []Record
		want    string
	}{
		{"cross-file", []Record{{Path: "other.go", StartLine: 1, EndLine: 1, Kind: "symbol", Text: "fact"}}, "another file"},
		{"duplicate", []Record{
			{ID: "same", StartLine: 1, EndLine: 1, Kind: "symbol", Text: "one"},
			{ID: "same", StartLine: 2, EndLine: 2, Kind: "symbol", Text: "two"},
		}, "duplicate record id"},
	} {
		t.Run(test.name, func(t *testing.T) {
			_, err := NormalizeRecords(descriptor, file, test.records)
			if err == nil || !strings.Contains(err.Error(), test.want) {
				t.Fatalf("err=%v want substring %q", err, test.want)
			}
		})
	}
}
