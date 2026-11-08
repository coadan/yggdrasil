package chunk

import (
	"fmt"
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

func TestRecordLinesMatchTrimmedText(t *testing.T) {
	// Blank lines at chunk edges, including at overlap boundaries and CRLF
	// blank lines, must not shift the cited lines of the trimmed text.
	var lines []string
	for index := range 260 {
		switch {
		case index%7 == 0:
			lines = append(lines, "")
		case index%11 == 0:
			lines = append(lines, "\r")
		default:
			lines = append(lines, fmt.Sprintf("  line %d", index+1))
		}
	}
	content := "\n\n" + strings.Join(lines, "\n") + "\n\n"
	fileLines := strings.Split(content, "\n")
	records := Records(discovery.File{
		Candidate: discovery.Candidate{Path: "a.txt"}, Kind: "txt", Content: content,
	})
	if len(records) < 3 {
		t.Fatalf("records = %d", len(records))
	}
	for _, record := range records[1:] {
		textLines := strings.Split(record.Text, "\n")
		if got := record.EndLine - record.StartLine + 1; got != len(textLines) {
			t.Fatalf("record %d-%d spans %d lines, text has %d", record.StartLine, record.EndLine, got, len(textLines))
		}
		for index, text := range textLines {
			want := strings.TrimRight(fileLines[record.StartLine-1+index], " \t\r")
			if strings.TrimRight(text, " \t\r") != want {
				t.Fatalf("record %d-%d line %d = %q, file has %q",
					record.StartLine, record.EndLine, index, text, want)
			}
		}
	}
}
