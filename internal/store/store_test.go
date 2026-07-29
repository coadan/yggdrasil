package store

import (
	"context"
	"path/filepath"
	"testing"

	"github.com/coadan/yggdrasil/internal/contracts"
	"github.com/coadan/yggdrasil/internal/discovery"
)

func TestReplaceAndDeleteFileOwnsRecords(t *testing.T) {
	ctx := context.Background()
	value, err := Open(ctx, filepath.Join(t.TempDir(), "search.sqlite3"), "/repo", "root-id")
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	file := discovery.File{
		Candidate: discovery.Candidate{Path: "a.txt", Size: 5, MTimeNS: 1},
		Kind:      "txt",
		Content:   "hello",
	}
	records := []contracts.SearchRecord{{
		Path: "a.txt", StartLine: 1, EndLine: 1, Kind: "text", Text: "hello", Source: "core",
	}}
	if err := value.ReplaceFile(ctx, "run-1", file, "hash", "fingerprint", records); err != nil {
		t.Fatal(err)
	}
	counts, err := value.Counts(ctx)
	if err != nil || counts.Files != 1 || counts.Records != 1 {
		t.Fatalf("counts=%#v err=%v", counts, err)
	}
	if err := value.DeleteFile(ctx, "a.txt"); err != nil {
		t.Fatal(err)
	}
	counts, err = value.Counts(ctx)
	if err != nil || counts.Files != 0 || counts.Records != 0 {
		t.Fatalf("counts after delete=%#v err=%v", counts, err)
	}
}

func TestOpenRejectsAnotherRoot(t *testing.T) {
	ctx := context.Background()
	path := filepath.Join(t.TempDir(), "search.sqlite3")
	first, err := Open(ctx, path, "/one", "one")
	if err != nil {
		t.Fatal(err)
	}
	first.Close()
	if _, err := Open(ctx, path, "/two", "two"); err == nil {
		t.Fatal("expected root mismatch")
	}
}

func TestEmbeddingLaneReturnsNearestRecord(t *testing.T) {
	ctx := context.Background()
	value, err := Open(ctx, filepath.Join(t.TempDir(), "search.sqlite3"), "/repo", "root")
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	for _, item := range []struct {
		path string
		text string
	}{
		{"near.txt", "near"},
		{"far.txt", "far"},
	} {
		file := discovery.File{
			Candidate: discovery.Candidate{Path: item.path, Size: int64(len(item.text)), MTimeNS: 1},
			Kind:      "txt",
		}
		if err := value.ReplaceFile(ctx, "run", file, item.text, "fingerprint", []contracts.SearchRecord{{
			Path: item.path, StartLine: 1, EndLine: 1, Kind: "text", Text: item.text, Source: "core",
		}}); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := value.PrepareEmbeddingLane(ctx, "embed-fp", "model", 2); err != nil {
		t.Fatal(err)
	}
	inputs, err := value.MissingEmbeddingInputs(ctx, "embed-fp", 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(inputs) != 2 {
		t.Fatalf("inputs=%#v", inputs)
	}
	vectors := make([]EmbeddingValue, 0, 2)
	for _, input := range inputs {
		vector := []float32{0, 1}
		if input.Text == "near" {
			vector = []float32{1, 0}
		}
		vectors = append(vectors, EmbeddingValue{
			ID: input.ID, InputHash: input.InputHash, Vector: vector,
		})
	}
	if err := value.UpsertEmbeddings(ctx, "embed-fp", 2, vectors); err != nil {
		t.Fatal(err)
	}
	state, err := value.EmbeddingState(ctx, "embed-fp")
	if err != nil || !state.Complete || state.Embedded != 2 {
		t.Fatalf("state=%#v err=%v", state, err)
	}
	records, err := value.VectorCandidates(ctx, []float32{1, 0}, 1)
	if err != nil {
		t.Fatal(err)
	}
	if len(records) != 1 || records[0].Path != "near.txt" {
		t.Fatalf("records=%#v", records)
	}
}
