package chunk

import (
	"strings"
	"testing"

	"github.com/coadan/yggdrasil/internal/contracts"
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

func TestRecordsEmbedSmallWindowsInsteadOfChunks(t *testing.T) {
	lines := make([]string, 200)
	for i := range lines {
		lines[i] = strings.Repeat("x", 40)
	}
	records := Records(discovery.File{
		Candidate: discovery.Candidate{Path: "a.txt"},
		Kind:      "txt",
		Content:   strings.Join(lines, "\n"),
	})
	var windows []contracts.SearchRecord
	chunks := 0
	for _, record := range records[1:] {
		if record.Metadata["semantic"] == false {
			chunks++
			continue
		}
		if record.Metadata["lexical"] != false {
			t.Fatalf("window metadata = %#v", record.Metadata)
		}
		if record.EndLine-record.StartLine+1 > WindowLines || len(record.Text) > WindowBytes {
			t.Fatalf("unbounded window: %d-%d, %d bytes", record.StartLine, record.EndLine, len(record.Text))
		}
		windows = append(windows, record)
	}
	if chunks != 2 {
		t.Fatalf("lexical chunks = %d", chunks)
	}
	if len(windows) != 2*WindowsPerChunk {
		t.Fatalf("windows = %d, want %d", len(windows), 2*WindowsPerChunk)
	}
	if windows[0].StartLine != 1 || windows[len(windows)-1].EndLine != len(lines) {
		t.Fatalf("windows span %d-%d, want 1-%d",
			windows[0].StartLine, windows[len(windows)-1].EndLine, len(lines))
	}
	for i := 1; i < len(windows); i++ {
		if windows[i].StartLine <= windows[i-1].StartLine {
			t.Fatalf("windows out of order: %d after %d", windows[i].StartLine, windows[i-1].StartLine)
		}
	}
}
