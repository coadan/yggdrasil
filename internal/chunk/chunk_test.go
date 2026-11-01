package chunk

import (
	"strings"
	"testing"

	"github.com/coadan/yggdrasil/internal/discovery"
)

func TestRecordsAreBoundedAndCited(t *testing.T) {
	lines := make([]string, 400)
	for i := range lines {
		lines[i] = "line"
	}
	records := Records(discovery.File{
		Candidate: discovery.Candidate{Path: "a.txt"},
		Kind:      "txt",
		Content:   strings.Join(lines, "\n"),
	})
	if len(records) < 4 {
		t.Fatalf("records = %d", len(records))
	}
	for _, record := range records[1:] {
		if record.EndLine-record.StartLine+1 > MaxLines || len(record.Text) > MaxBytes {
			t.Fatalf("unbounded record: %#v", record)
		}
	}
}

func TestRecordsSplitLongLinesWithoutEmbeddingThem(t *testing.T) {
	records := Records(discovery.File{
		Candidate: discovery.Candidate{Path: "logo.svg"},
		Kind:      "svg",
		Content:   strings.Repeat("<path d='λ'/>", MaxBytes),
	})
	if len(records) < 3 {
		t.Fatalf("records = %d", len(records))
	}
	for _, record := range records[1:] {
		if len(record.Text) > MaxBytes {
			t.Fatalf("record has %d bytes", len(record.Text))
		}
		if record.StartLine != 1 || record.EndLine != 1 {
			t.Fatalf("citation = %d-%d", record.StartLine, record.EndLine)
		}
		if semantic, ok := record.Metadata["semantic"].(bool); !ok || semantic {
			t.Fatalf("metadata = %#v", record.Metadata)
		}
	}
}
