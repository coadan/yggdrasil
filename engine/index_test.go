package engine

import (
	"context"
	"testing"
	"time"

	"github.com/coadan/yggdrasil/index"
)

type recordingRepository struct {
	requests chan index.RefreshOptions
}

func (r *recordingRepository) Refresh(_ context.Context, opts index.RefreshOptions) (index.RefreshResult, error) {
	r.requests <- opts
	return index.RefreshResult{
		EmbeddingStatus: "partial",
		Coverage:        index.Readiness{Embedded: 1, Records: 2},
	}, nil
}

func TestIndexRefresherMapsAgingAndDemandToBoundedRefresh(t *testing.T) {
	repository := &recordingRepository{requests: make(chan index.RefreshOptions, 2)}
	value, err := StartIndexRefresher(context.Background(), RefresherOptions{
		Interval: time.Hour, RetryBackoff: time.Hour,
		WorkTimeout: time.Second, AgingEvery: 3,
	}, repository)
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	initial := <-repository.requests
	if !initial.Aging || initial.EmbeddingBatches != 1 ||
		initial.PriorityScope != "" || len(initial.PriorityPaths) != 0 {
		t.Fatalf("initial=%#v", initial)
	}
	value.Wake(Demand{Scope: "src/", Paths: []string{"src/owner.go"}})
	select {
	case requested := <-repository.requests:
		if requested.Aging || requested.EmbeddingBatches != 1 ||
			requested.PriorityScope != "src/" || len(requested.PriorityPaths) != 1 ||
			requested.PriorityPaths[0] != "src/owner.go" {
			t.Fatalf("requested=%#v", requested)
		}
	case <-time.After(time.Second):
		t.Fatal("demand did not trigger refresh")
	}
}
