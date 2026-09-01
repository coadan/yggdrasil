package engine

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/coadan/yggdrasil/extractor"
	"github.com/coadan/yggdrasil/index"
	"github.com/coadan/yggdrasil/query"
)

type blockingExtractor struct {
	started chan struct{}
	release chan struct{}
}

func (p *blockingExtractor) Descriptor() extractor.Descriptor {
	return extractor.Descriptor{ID: "blocking", Fingerprint: "v1"}
}

func (p *blockingExtractor) Extract(
	_ context.Context,
	_ extractor.File,
) ([]extractor.Record, []extractor.Diagnostic, error) {
	select {
	case <-p.started:
	default:
		close(p.started)
	}
	<-p.release
	return nil, nil, nil
}

func TestServiceFallsBackWithoutWaitingThenUsesPublishedSnapshot(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(
		filepath.Join(root, "owner.go"),
		[]byte("package owner\n// unique retrieval fact\n"),
		0o644,
	); err != nil {
		t.Fatal(err)
	}
	provider := &blockingExtractor{started: make(chan struct{}), release: make(chan struct{})}
	repository, err := index.Open(index.Options{
		Root: root, StorageRoot: t.TempDir(), Extractor: provider,
	})
	if err != nil {
		t.Fatal(err)
	}
	service, err := StartService(context.Background(), repository, ServiceOptions{
		Refresher: RefresherOptions{
			Interval: time.Hour, RetryBackoff: time.Hour,
			WorkTimeout: time.Second, AgingEvery: 3,
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close()
	select {
	case <-provider.started:
	case <-time.After(time.Second):
		t.Fatal("initial refresh did not start")
	}
	searchCtx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()
	fallback, err := service.Search(searchCtx, "unique retrieval", query.Options{Mode: "auto", Limit: 5})
	if err != nil {
		close(provider.release)
		t.Fatal(err)
	}
	if fallback.FallbackReason != "index-stale" || len(fallback.Records) != 1 ||
		fallback.Records[0].Path != "owner.go" {
		close(provider.release)
		t.Fatalf("fallback=%#v", fallback)
	}
	close(provider.release)
	deadline := time.Now().Add(time.Second)
	for service.Status().LastSuccess.IsZero() {
		if time.Now().After(deadline) {
			t.Fatalf("status=%#v", service.Status())
		}
		time.Sleep(time.Millisecond)
	}
	current, err := service.Search(context.Background(), "unique retrieval", query.Options{
		Mode: "lexical", Limit: 5,
	})
	if err != nil {
		t.Fatal(err)
	}
	if current.FallbackReason != "" || len(current.Records) != 1 ||
		current.Records[0].Path != "owner.go" {
		t.Fatalf("current=%#v", current)
	}
}
