package store

import (
	"context"
	"fmt"
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

func TestApplyBatchCommitsFilesDeletesAndDiagnostics(t *testing.T) {
	ctx := context.Background()
	value, err := Open(ctx, filepath.Join(t.TempDir(), "search.sqlite3"), "/repo", "root-id")
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	first := discovery.File{
		Candidate: discovery.Candidate{Path: "old.txt", Size: 3, MTimeNS: 1},
		Kind:      "txt",
	}
	if err := value.ReplaceFile(ctx, "run-1", first, "old", "fingerprint", []contracts.SearchRecord{{
		Path: first.Path, StartLine: 1, EndLine: 1, Kind: "text", Text: "old", Source: "core",
	}}); err != nil {
		t.Fatal(err)
	}
	second := discovery.File{
		Candidate: discovery.Candidate{Path: "new.txt", Size: 3, MTimeNS: 2},
		Kind:      "txt",
	}
	if err := value.ApplyBatch(ctx, "run-2", nil, []FileUpdate{{
		File: second, ContentHash: "new", ExtractionFingerprint: "fingerprint",
		Records: []contracts.SearchRecord{{
			Path: second.Path, StartLine: 1, EndLine: 1, Kind: "text", Text: "new", Source: "core",
		}},
	}}, []string{first.Path}, []Diagnostic{{
		Path: second.Path, Stage: "test", Message: "bounded diagnostic",
	}}); err != nil {
		t.Fatal(err)
	}
	states, err := value.FileStates(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(states) != 1 || states[second.Path].ContentHash != "new" {
		t.Fatalf("states=%#v", states)
	}
	counts, err := value.Counts(ctx)
	if err != nil || counts.Files != 1 || counts.Records != 1 || counts.Diagnostics != 1 {
		t.Fatalf("counts=%#v err=%v", counts, err)
	}
}

func TestApplyBatchRollsBackAllFilesOnFailure(t *testing.T) {
	ctx := context.Background()
	value, err := Open(ctx, filepath.Join(t.TempDir(), "search.sqlite3"), "/repo", "root-id")
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	file := discovery.File{
		Candidate: discovery.Candidate{Path: "bad.txt", Size: 3, MTimeNS: 1},
		Kind:      "txt",
	}
	duplicate := contracts.SearchRecord{
		ID: "same", Path: file.Path, StartLine: 1, EndLine: 1, Kind: "text", Text: "bad", Source: "core",
	}
	err = value.ApplyBatch(ctx, "run", nil, []FileUpdate{{
		File: file, ContentHash: "bad", ExtractionFingerprint: "fingerprint",
		Records: []contracts.SearchRecord{duplicate, duplicate},
	}}, nil, nil)
	if err == nil {
		t.Fatal("expected duplicate record failure")
	}
	counts, countErr := value.Counts(ctx)
	if countErr != nil || counts.Files != 0 || counts.Records != 0 {
		t.Fatalf("counts=%#v err=%v", counts, countErr)
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
	records, err := value.VectorCandidates(ctx, []float32{1, 0}, "", 1)
	if err != nil {
		t.Fatal(err)
	}
	if len(records) != 1 || records[0].Path != "near.txt" {
		t.Fatalf("records=%#v", records)
	}
}

func TestVectorCandidatesApplyScopeBeforeLimit(t *testing.T) {
	ctx := context.Background()
	value, err := Open(ctx, filepath.Join(t.TempDir(), "search.sqlite3"), "/repo", "root")
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	items := []struct {
		path   string
		text   string
		vector []float32
	}{
		{path: "src/app/owner.ts", text: "scoped owner", vector: []float32{0.9, 0.1}},
	}
	for index := range 101 {
		items = append(items, struct {
			path   string
			text   string
			vector []float32
		}{
			path:   fmt.Sprintf("scripts/noise-%03d.ts", index),
			text:   fmt.Sprintf("outside noise %03d", index),
			vector: []float32{1, 0},
		})
	}
	for _, item := range items {
		file := discovery.File{
			Candidate: discovery.Candidate{
				Path: item.path, Size: int64(len(item.text)), MTimeNS: 1,
			},
			Kind: "typescript",
		}
		if err := value.ReplaceFile(ctx, "run", file, item.text, "fingerprint", []contracts.SearchRecord{{
			Path: item.path, StartLine: 1, EndLine: 1, Kind: "text",
			Text: item.text, Source: "core",
		}}); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := value.PrepareEmbeddingLane(ctx, "embed-fp", "model", 2); err != nil {
		t.Fatal(err)
	}
	inputs, err := value.MissingEmbeddingInputs(ctx, "embed-fp", len(items))
	if err != nil || len(inputs) != len(items) {
		t.Fatalf("inputs=%d err=%v", len(inputs), err)
	}
	vectors := make([]EmbeddingValue, len(inputs))
	for index, input := range inputs {
		vectors[index] = EmbeddingValue{
			ID: input.ID, InputHash: input.InputHash, Vector: items[index].vector,
		}
	}
	if err := value.UpsertEmbeddings(ctx, "embed-fp", 2, vectors); err != nil {
		t.Fatal(err)
	}
	records, err := value.VectorCandidates(ctx, []float32{1, 0}, "src/app/", 1)
	if err != nil {
		t.Fatal(err)
	}
	if len(records) != 1 || records[0].Path != "src/app/owner.ts" {
		t.Fatalf("records=%#v", records)
	}
	records, err = value.VectorCandidates(ctx, []float32{1, 0}, "src/app/owner.ts", 1)
	if err != nil {
		t.Fatal(err)
	}
	if len(records) != 1 || records[0].Path != "src/app/owner.ts" {
		t.Fatalf("file-scoped records=%#v", records)
	}
}

func TestApplyBatchInvalidatesReplacedEmbeddings(t *testing.T) {
	ctx := context.Background()
	value, err := Open(ctx, filepath.Join(t.TempDir(), "search.sqlite3"), "/repo", "root")
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	file := discovery.File{
		Candidate: discovery.Candidate{Path: "replace.txt", Size: 3, MTimeNS: 1},
		Kind:      "txt",
	}
	if err := value.ReplaceFile(ctx, "run-1", file, "old", "fingerprint", []contracts.SearchRecord{{
		Path: file.Path, StartLine: 1, EndLine: 1, Kind: "text", Text: "old", Source: "core",
	}}); err != nil {
		t.Fatal(err)
	}
	if _, err := value.PrepareEmbeddingLane(ctx, "embed-fp", "model", 2); err != nil {
		t.Fatal(err)
	}
	inputs, err := value.MissingEmbeddingInputs(ctx, "embed-fp", 10)
	if err != nil || len(inputs) != 1 {
		t.Fatalf("inputs=%#v err=%v", inputs, err)
	}
	if err := value.UpsertEmbeddings(ctx, "embed-fp", 2, []EmbeddingValue{{
		ID: inputs[0].ID, InputHash: inputs[0].InputHash, Vector: []float32{1, 0},
	}}); err != nil {
		t.Fatal(err)
	}
	file.MTimeNS = 2
	if err := value.ApplyBatch(ctx, "run-2", nil, []FileUpdate{{
		File: file, ContentHash: "new", ExtractionFingerprint: "fingerprint",
		Records: []contracts.SearchRecord{{
			Path: file.Path, StartLine: 1, EndLine: 1, Kind: "text", Text: "new", Source: "core",
		}},
	}}, nil, nil); err != nil {
		t.Fatal(err)
	}
	state, err := value.EmbeddingState(ctx, "embed-fp")
	if err != nil {
		t.Fatal(err)
	}
	if state.Records != 1 || state.Embedded != 0 || state.Complete {
		t.Fatalf("state=%#v", state)
	}
}
