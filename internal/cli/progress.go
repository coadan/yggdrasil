package cli

import (
	"encoding/json"
	"io"
	"time"

	"github.com/coadan/yggdrasil/internal/indexer"
)

const progressInterval = 500 * time.Millisecond

type progressReporter struct {
	encoder    *json.Encoder
	lastPhase  string
	lastReport time.Time
}

func newProgressReporter(writer io.Writer) *progressReporter {
	encoder := json.NewEncoder(writer)
	encoder.SetEscapeHTML(false)
	return &progressReporter{encoder: encoder}
}

func (r *progressReporter) Report(progress indexer.Progress) {
	now := time.Now()
	terminal := progress.Phase == "complete" || progress.Phase == "failed" ||
		progress.Completed == progress.Total
	if progress.Phase == r.lastPhase && !terminal &&
		now.Sub(r.lastReport) < progressInterval {
		return
	}
	if r.encoder.Encode(progress) == nil {
		r.lastPhase = progress.Phase
		r.lastReport = now
	}
}
