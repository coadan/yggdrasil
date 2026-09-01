package store

import (
	"context"
	"fmt"
	"path/filepath"
	"reflect"
	"strings"
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

func TestOpenMigratesSemanticOnlyRecordsOutOfLexicalIndexes(t *testing.T) {
	ctx := context.Background()
	path := filepath.Join(t.TempDir(), "search.sqlite3")
	value, err := Open(ctx, path, "/repo", "root")
	if err != nil {
		t.Fatal(err)
	}
	file := discovery.File{
		Candidate: discovery.Candidate{Path: "owner.go", Size: 6, MTimeNS: 1}, Kind: "go",
	}
	if err := value.ReplaceFile(ctx, "run", file, "hash", "fingerprint", []contracts.SearchRecord{
		{Path: file.Path, StartLine: 1, EndLine: 1, Kind: "go-function", Text: "visible", Source: "plugin:go"},
		{Path: file.Path, StartLine: 1, EndLine: 2, Kind: "go-structural", Text: "hidden", Source: "plugin:go", Metadata: map[string]any{"lexical": false}},
	}); err != nil {
		t.Fatal(err)
	}
	var hiddenID int64
	if err := value.db.QueryRowContext(ctx, `SELECT id FROM records WHERE text='hidden'`).Scan(&hiddenID); err != nil {
		t.Fatal(err)
	}
	for _, statement := range []struct {
		query string
		args  []any
	}{
		{`INSERT INTO record_fts(rowid,path,title,text) VALUES(?,'owner.go','','hidden')`, []any{hiddenID}},
		{`INSERT INTO extractor_fts(rowid,path,title,text) VALUES(?,'owner.go','','hidden')`, []any{hiddenID}},
		{`UPDATE meta SET value='2' WHERE key='schema_version'`, nil},
		{`UPDATE meta SET value='1' WHERE key='extractor_fts_version'`, nil},
	} {
		if _, err := value.db.ExecContext(ctx, statement.query, statement.args...); err != nil {
			t.Fatal(err)
		}
	}
	if err := value.Close(); err != nil {
		t.Fatal(err)
	}
	value, err = Open(ctx, path, "/repo", "root")
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	for _, table := range []string{"record_fts", "extractor_fts"} {
		var visible, hidden int
		if err := value.db.QueryRowContext(ctx, `SELECT count(*) FROM `+table+` WHERE `+table+` MATCH 'visible'`).Scan(&visible); err != nil {
			t.Fatal(err)
		}
		if err := value.db.QueryRowContext(ctx, `SELECT count(*) FROM `+table+` WHERE `+table+` MATCH 'hidden'`).Scan(&hidden); err != nil {
			t.Fatal(err)
		}
		if visible != 1 || hidden != 0 {
			t.Fatalf("%s visible=%d hidden=%d", table, visible, hidden)
		}
	}
}

func TestGraphCandidatesFollowResolvedPluginImports(t *testing.T) {
	ctx := context.Background()
	value, err := Open(ctx, filepath.Join(t.TempDir(), "search.sqlite3"), "/repo", "root")
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	for _, item := range []struct {
		path    string
		records []contracts.SearchRecord
	}{
		{
			path: "src/owner.ts",
			records: []contracts.SearchRecord{
				{Path: "src/owner.ts", StartLine: 1, EndLine: 1, Kind: "file", Text: "src/owner.ts", Source: "core"},
				{Path: "src/owner.ts", StartLine: 4, EndLine: 6, Kind: "typescript-function", Title: "ownRoute", Text: "function ownRoute", Source: "plugin:typescript"},
			},
		},
		{
			path: "src/consumer.ts",
			records: []contracts.SearchRecord{
				{Path: "src/consumer.ts", StartLine: 1, EndLine: 1, Kind: "file", Text: "src/consumer.ts", Source: "core"},
				{Path: "src/consumer.ts", StartLine: 2, EndLine: 2, Kind: "typescript-import", Title: "./owner", Text: `import { ownRoute } from "./owner"`, Source: "plugin:typescript"},
				{Path: "src/consumer.ts", StartLine: 3, EndLine: 3, Kind: "typescript-import", Title: "react", Text: `import React from "react"`, Source: "plugin:typescript"},
			},
		},
	} {
		file := discovery.File{
			Candidate: discovery.Candidate{Path: item.path, Size: 20, MTimeNS: 1},
			Kind:      "typescript",
		}
		if err := value.ReplaceFile(ctx, "run", file, item.path, "fingerprint", item.records); err != nil {
			t.Fatal(err)
		}
	}
	if err := value.RebuildGraph(ctx); err != nil {
		t.Fatal(err)
	}
	counts, err := value.Counts(ctx)
	if err != nil || counts.GraphEdges != 2 {
		t.Fatalf("counts=%#v err=%v", counts, err)
	}
	outgoing, err := value.GraphCandidates(ctx, []string{"src/consumer.ts"}, "", 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(outgoing) != 1 || outgoing[0].Path != "src/owner.ts" || outgoing[0].Kind != "typescript-function" {
		t.Fatalf("outgoing=%#v", outgoing)
	}
	incoming, err := value.GraphCandidates(ctx, []string{"src/owner.ts"}, "src", 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(incoming) != 1 || incoming[0].Path != "src/consumer.ts" || incoming[0].Kind != "typescript-import" {
		t.Fatalf("incoming=%#v", incoming)
	}
	outsideScope, err := value.GraphCandidates(ctx, []string{"src/owner.ts"}, "src/other", 10)
	if err != nil || len(outsideScope) != 0 {
		t.Fatalf("outside scope=%#v err=%v", outsideScope, err)
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
	if _, err := value.UpsertEmbeddings(ctx, "embed-fp", 2, vectors); err != nil {
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

func TestEmbeddingLaneExcludesLexicalOnlyExtractorFacts(t *testing.T) {
	ctx := context.Background()
	value, err := Open(ctx, filepath.Join(t.TempDir(), "search.sqlite3"), "/repo", "root")
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	var indexCount int
	if err := value.db.QueryRowContext(ctx, `
		SELECT count(*) FROM sqlite_master
		WHERE type='index' AND name='records_input_hash_idx'`).Scan(&indexCount); err != nil || indexCount != 1 {
		t.Fatalf("embedding input index=%d err=%v", indexCount, err)
	}
	file := discovery.File{
		Candidate: discovery.Candidate{Path: "owner.ts", Size: 20, MTimeNS: 1},
		Kind:      "typescript",
	}
	if err := value.ReplaceFile(ctx, "run", file, "hash", "fingerprint", []contracts.SearchRecord{
		{Path: file.Path, StartLine: 1, EndLine: 1, Kind: "typescript-navigation", Text: "semantic summary", Source: "plugin:typescript", Metadata: map[string]any{"lexical": false}},
		{Path: file.Path, StartLine: 2, EndLine: 2, Kind: "typescript-import", Text: "lexical fact", Source: "plugin:typescript", Metadata: map[string]any{"semantic": false}},
	}); err != nil {
		t.Fatal(err)
	}
	if _, err := value.PrepareEmbeddingLane(ctx, "embed-fp", "model", 2); err != nil {
		t.Fatal(err)
	}
	inputs, err := value.MissingEmbeddingInputs(ctx, "embed-fp", 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(inputs) != 1 || inputs[0].Text != "semantic summary" {
		t.Fatalf("inputs=%#v", inputs)
	}
	state, err := value.EmbeddingState(ctx, "embed-fp")
	if err != nil || state.Records != 1 || state.Embedded != 0 || state.Complete {
		t.Fatalf("state=%#v err=%v", state, err)
	}
}

func TestEmbeddingStateForScopeExcludesOtherPaths(t *testing.T) {
	ctx := context.Background()
	value, err := Open(ctx, filepath.Join(t.TempDir(), "search.sqlite3"), "/repo", "root")
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	for _, path := range []string{"src/ready.go", "docs/missing.md"} {
		file := discovery.File{
			Candidate: discovery.Candidate{Path: path, Size: 20, MTimeNS: 1},
			Kind:      "text",
		}
		if err := value.ReplaceFile(ctx, "run", file, path, "fingerprint", []contracts.SearchRecord{{
			Path: path, StartLine: 1, EndLine: 1, Kind: "text", Text: path, Source: "core",
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
	var ready EmbeddingInput
	for _, input := range inputs {
		if input.Text == "src/ready.go" {
			ready = input
		}
	}
	if ready.ID == 0 {
		t.Fatalf("inputs=%#v", inputs)
	}
	if _, err := value.UpsertEmbeddings(ctx, "embed-fp", 2, []EmbeddingValue{{
		ID: ready.ID, InputHash: ready.InputHash, Vector: []float32{1, 0},
	}}); err != nil {
		t.Fatal(err)
	}
	scoped, err := value.EmbeddingStateForScope(ctx, "embed-fp", "src/")
	if err != nil || !scoped.Complete || scoped.Records != 1 || scoped.Embedded != 1 {
		t.Fatalf("scoped=%#v err=%v", scoped, err)
	}
	all, err := value.EmbeddingState(ctx, "embed-fp")
	if err != nil || all.Complete || all.Records != 2 || all.Embedded != 1 {
		t.Fatalf("all=%#v err=%v", all, err)
	}
}

func TestMissingEmbeddingInputsPreservesRankedPathsThenScope(t *testing.T) {
	ctx := context.Background()
	value, err := Open(ctx, filepath.Join(t.TempDir(), "search.sqlite3"), "/repo", "root")
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	for _, item := range []struct{ path, text string }{
		{"cold/short.txt", "x"},
		{"changed/owner.txt", "changed record"},
		{"src/scoped.txt", "scoped medium text"},
		{"docs/first.txt", "first ranked path with deliberately longer text"},
		{"docs/second.txt", "second ranked path"},
	} {
		file := discovery.File{
			Candidate: discovery.Candidate{Path: item.path, Size: int64(len(item.text)), MTimeNS: 1},
			Kind:      "text",
		}
		if err := value.ReplaceFile(ctx, "run", file, item.text, "fingerprint", []contracts.SearchRecord{{
			Path: item.path, StartLine: 1, EndLine: 1, Kind: "text", Text: item.text, Source: "core",
		}}); err != nil {
			t.Fatal(err)
		}
	}
	prioritized, err := value.MissingEmbeddingInputsByPriority(ctx, "embed-fp", 4, EmbeddingPriority{
		Paths: []string{"docs/first.txt", "docs/second.txt"}, Scope: "src/",
		ChangedPaths: []string{"changed/owner.txt"},
	})
	if err != nil {
		t.Fatal(err)
	}
	got := make([]string, 0, len(prioritized))
	for _, input := range prioritized {
		got = append(got, input.Text)
	}
	want := []string{
		"first ranked path with deliberately longer text",
		"second ranked path",
		"scoped medium text",
		"changed record",
	}
	if strings.Join(got, "|") != strings.Join(want, "|") {
		t.Fatalf("got=%q want=%q", got, want)
	}
	unprioritized, err := value.MissingEmbeddingInputs(ctx, "embed-fp", 1)
	if err != nil || len(unprioritized) != 1 || unprioritized[0].Text != "x" {
		t.Fatalf("unprioritized=%#v err=%v", unprioritized, err)
	}
}

func TestLexicalCandidateLimitCountsUniquePaths(t *testing.T) {
	ctx := context.Background()
	value, err := Open(ctx, filepath.Join(t.TempDir(), "search.sqlite3"), "/repo", "root")
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	noise := discovery.File{
		Candidate: discovery.Candidate{Path: "a-noise.txt", Size: 5, MTimeNS: 1},
		Kind:      "txt",
	}
	noiseRecords := make([]contracts.SearchRecord, 101)
	for index := range noiseRecords {
		noiseRecords[index] = contracts.SearchRecord{
			Path: noise.Path, StartLine: index + 1, EndLine: index + 1,
			Kind: "text", Text: "needle", Source: "core",
		}
	}
	if err := value.ReplaceFile(ctx, "run", noise, "noise", "fingerprint", noiseRecords); err != nil {
		t.Fatal(err)
	}
	owner := discovery.File{
		Candidate: discovery.Candidate{Path: "z-owner.txt", Size: 5, MTimeNS: 1},
		Kind:      "txt",
	}
	if err := value.ReplaceFile(ctx, "run", owner, "owner", "fingerprint", []contracts.SearchRecord{{
		Path: owner.Path, StartLine: 1, EndLine: 1, Kind: "text", Text: "needle", Source: "core",
	}}); err != nil {
		t.Fatal(err)
	}
	records, err := value.LexicalCandidates(ctx, "needle", "", 2)
	if err != nil {
		t.Fatal(err)
	}
	paths := firstRecordsByPath(records, 2)
	if len(paths) != 2 || paths[0].Path != noise.Path || paths[1].Path != owner.Path {
		t.Fatalf("records=%#v", records)
	}
}

func TestLexicalCandidatesRespectExplicitRecordOptOut(t *testing.T) {
	ctx := context.Background()
	value, err := Open(ctx, filepath.Join(t.TempDir(), "search.sqlite3"), "/repo", "root")
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	for _, item := range []struct {
		path     string
		metadata map[string]any
	}{
		{path: "hidden.go", metadata: map[string]any{"lexical": false}},
		{path: "visible.go"},
	} {
		file := discovery.File{
			Candidate: discovery.Candidate{Path: item.path, Size: 6, MTimeNS: 1}, Kind: "go",
		}
		if err := value.ReplaceFile(ctx, "run", file, item.path, "fingerprint", []contracts.SearchRecord{{
			Path: item.path, StartLine: 1, EndLine: 1, Kind: "go-navigation",
			Text: "needle", Source: "plugin:go", Metadata: item.metadata,
		}}); err != nil {
			t.Fatal(err)
		}
	}
	queries := []func() ([]Record, error){
		func() ([]Record, error) { return value.LexicalCandidates(ctx, "needle", "", 10) },
		func() ([]Record, error) { return value.LiteralCandidates(ctx, "needle", "needle", "", 10) },
		func() ([]Record, error) { return value.ExtractorCandidates(ctx, "needle", "", 10) },
	}
	for _, query := range queries {
		records, err := query()
		if err != nil || len(records) != 1 || records[0].Path != "visible.go" {
			t.Fatalf("records=%#v err=%v", records, err)
		}
	}
}

func TestVectorCandidateLimitCountsUniquePaths(t *testing.T) {
	ctx := context.Background()
	value, err := Open(ctx, filepath.Join(t.TempDir(), "search.sqlite3"), "/repo", "root")
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	noise := discovery.File{
		Candidate: discovery.Candidate{Path: "a-noise.txt", Size: 5, MTimeNS: 1},
		Kind:      "txt",
	}
	noiseRecords := make([]contracts.SearchRecord, 101)
	for index := range noiseRecords {
		noiseRecords[index] = contracts.SearchRecord{
			Path: noise.Path, StartLine: index + 1, EndLine: index + 1,
			Kind: "text", Text: fmt.Sprintf("noise-%03d", index), Source: "core",
		}
	}
	if err := value.ReplaceFile(ctx, "run", noise, "noise", "fingerprint", noiseRecords); err != nil {
		t.Fatal(err)
	}
	owner := discovery.File{
		Candidate: discovery.Candidate{Path: "z-owner.txt", Size: 5, MTimeNS: 1},
		Kind:      "txt",
	}
	if err := value.ReplaceFile(ctx, "run", owner, "owner", "fingerprint", []contracts.SearchRecord{{
		Path: owner.Path, StartLine: 1, EndLine: 1, Kind: "text", Text: "owner", Source: "core",
	}}); err != nil {
		t.Fatal(err)
	}
	if _, err := value.PrepareEmbeddingLane(ctx, "embed-fp", "model", 2); err != nil {
		t.Fatal(err)
	}
	inputs, err := value.MissingEmbeddingInputs(ctx, "embed-fp", 200)
	if err != nil {
		t.Fatal(err)
	}
	vectors := make([]EmbeddingValue, len(inputs))
	for index, input := range inputs {
		vector := []float32{1, 0}
		if input.Text == "owner" {
			vector = []float32{0.9, 0.1}
		}
		vectors[index] = EmbeddingValue{ID: input.ID, InputHash: input.InputHash, Vector: vector}
	}
	if _, err := value.UpsertEmbeddings(ctx, "embed-fp", 2, vectors); err != nil {
		t.Fatal(err)
	}
	records, err := value.VectorCandidates(ctx, []float32{1, 0}, "", 2)
	if err != nil {
		t.Fatal(err)
	}
	paths := firstRecordsByPath(records, 2)
	if len(paths) != 2 || paths[0].Path != noise.Path || paths[1].Path != owner.Path {
		t.Fatalf("records=%#v", records)
	}
}

func TestEmbeddingLaneDeduplicatesInputsAndSkipsPathRecords(t *testing.T) {
	ctx := context.Background()
	value, err := Open(ctx, filepath.Join(t.TempDir(), "search.sqlite3"), "/repo", "root")
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	for _, path := range []string{"one.txt", "two.txt"} {
		file := discovery.File{
			Candidate: discovery.Candidate{Path: path, Size: 6, MTimeNS: 1},
			Kind:      "txt",
		}
		if err := value.ReplaceFile(ctx, "run", file, "same", "fingerprint", []contracts.SearchRecord{
			{Path: path, StartLine: 1, EndLine: 1, Kind: "file", Title: path, Text: path, Source: "core"},
			{Path: path, StartLine: 1, EndLine: 1, Kind: "text", Text: "shared", Source: "core"},
		}); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := value.PrepareEmbeddingLane(ctx, "embed-fp", "model", 2); err != nil {
		t.Fatal(err)
	}
	inputs, err := value.MissingEmbeddingInputs(ctx, "embed-fp", 10)
	if err != nil || len(inputs) != 1 || inputs[0].Text != "shared" {
		t.Fatalf("deduplicated inputs=%#v err=%v", inputs, err)
	}
	upserted, err := value.UpsertEmbeddings(ctx, "embed-fp", 2, []EmbeddingValue{{
		ID: inputs[0].ID, InputHash: inputs[0].InputHash, Vector: []float32{1, 0},
	}})
	if err != nil || upserted != 2 {
		t.Fatalf("upserted=%d err=%v", upserted, err)
	}
	state, err := value.EmbeddingState(ctx, "embed-fp")
	if err != nil || state.Records != 2 || state.Embedded != 2 || !state.Complete {
		t.Fatalf("state=%#v err=%v", state, err)
	}
}

func TestMissingEmbeddingInputsGroupsSimilarLengths(t *testing.T) {
	ctx := context.Background()
	value, err := Open(ctx, filepath.Join(t.TempDir(), "search.sqlite3"), "/repo", "root")
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	for index, text := range []string{"a much longer input", "tiny", "medium text"} {
		path := fmt.Sprintf("%d.txt", index)
		file := discovery.File{
			Candidate: discovery.Candidate{Path: path, Size: int64(len(text)), MTimeNS: 1},
			Kind:      "txt",
		}
		if err := value.ReplaceFile(ctx, "run", file, text, "fingerprint", []contracts.SearchRecord{{
			Path: path, StartLine: 1, EndLine: 1, Kind: "text", Text: text, Source: "core",
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
	got := make([]string, len(inputs))
	for index, input := range inputs {
		got[index] = input.Text
	}
	want := []string{"tiny", "medium text", "a much longer input"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("inputs=%q want=%q", got, want)
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
	if _, err := value.UpsertEmbeddings(ctx, "embed-fp", 2, vectors); err != nil {
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
	if _, err := value.UpsertEmbeddings(ctx, "embed-fp", 2, []EmbeddingValue{{
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
