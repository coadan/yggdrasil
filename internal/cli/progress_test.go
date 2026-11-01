package cli

import (
	"bytes"
	"encoding/json"
	"strings"
	"testing"

	"github.com/coadan/yggdrasil/internal/indexer"
)

func TestProgressReporterThrottlesIntermediateEventsButKeepsCompletion(t *testing.T) {
	var output bytes.Buffer
	reporter := newProgressReporter(&output)
	reporter.Report(indexer.Progress{Schema: "ygg.index.progress/v1", Phase: "index", Total: 10})
	reporter.Report(indexer.Progress{Schema: "ygg.index.progress/v1", Phase: "index", Completed: 1, Total: 10})
	reporter.Report(indexer.Progress{Schema: "ygg.index.progress/v1", Phase: "index", Completed: 10, Total: 10})

	lines := strings.Split(strings.TrimSpace(output.String()), "\n")
	if len(lines) != 2 {
		t.Fatalf("progress events=%d output=%s", len(lines), output.String())
	}
	var final indexer.Progress
	if err := json.Unmarshal([]byte(lines[1]), &final); err != nil {
		t.Fatal(err)
	}
	if final.Completed != 10 || final.Total != 10 {
		t.Fatalf("final progress=%#v", final)
	}
}

func TestProgressReporterKeepsZeroWorkTerminalEvent(t *testing.T) {
	var output bytes.Buffer
	reporter := newProgressReporter(&output)
	reporter.Report(indexer.Progress{Schema: "ygg.index.progress/v1", Phase: "discovery"})
	reporter.Report(indexer.Progress{Schema: "ygg.index.progress/v1", Phase: "discovery"})

	lines := strings.Split(strings.TrimSpace(output.String()), "\n")
	if len(lines) != 2 {
		t.Fatalf("progress events=%d output=%s", len(lines), output.String())
	}
}
