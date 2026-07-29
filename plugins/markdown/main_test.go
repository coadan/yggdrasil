package main

import "testing"

func TestExtractsHeadingsAndFences(t *testing.T) {
	records := extract("# Title\nintro\n\n## Child\ntext\n\n```go\nfunc main() {}\n```\n")
	if len(records) != 3 {
		t.Fatalf("records = %#v", records)
	}
	if records[0].Kind != "markdown-section" || records[0].StartLine != 1 {
		t.Fatalf("heading = %#v", records[0])
	}
	if records[2].Kind != "markdown-fence" || records[2].Title != "go" {
		t.Fatalf("fence = %#v", records[2])
	}
}
