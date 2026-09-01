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
	r.report(progress.Phase, progress.Completed, progress.Total, progress)
}

func (r *progressReporter) report(phase string, completed, total int, progress any) {
	now := time.Now()
	terminal := phase == "complete" || phase == "failed" || completed == total
	if phase == r.lastPhase && !terminal &&
		now.Sub(r.lastReport) < progressInterval {
		return
	}
	if r.encoder.Encode(progress) == nil {
		r.lastPhase = phase
		r.lastReport = now
	}
}
