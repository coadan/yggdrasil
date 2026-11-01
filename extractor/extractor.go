// Package extractor defines provider-neutral repository extraction contracts.
package extractor

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"unicode/utf8"
)

const (
	MaxRecords       = 257
	MaxRecordText    = 64 * 1024
	MaxMetadataBytes = 16 * 1024
)

type Descriptor struct {
	ID          string
	Fingerprint string
}

type CommandSpec struct {
	ID           string
	Version      string
	Command      []string
	IncludeGlobs []string
	TimeoutMS    int
}

func CommandDescriptor(spec CommandSpec) Descriptor {
	payload, _ := json.Marshal(spec)
	sum := sha256.Sum256(payload)
	return Descriptor{
		ID: spec.ID, Fingerprint: "sha256:" + hex.EncodeToString(sum[:]),
	}
}

type File struct {
	Path        string
	Kind        string
	ContentHash string
	Content     string
}

type Record struct {
	ID        string         `json:"id,omitempty"`
	Path      string         `json:"path"`
	StartLine int            `json:"startLine"`
	EndLine   int            `json:"endLine"`
	Kind      string         `json:"kind"`
	Title     string         `json:"title,omitempty"`
	Text      string         `json:"text"`
	Metadata  map[string]any `json:"metadata,omitempty"`
	Source    string         `json:"source,omitempty"`
}

type Diagnostic struct {
	Path    string `json:"path,omitempty"`
	Plugin  string `json:"plugin"`
	Stage   string `json:"stage"`
	Message string `json:"message"`
}

// Provider is retained and closed by its caller. Extract must be bounded by
// the supplied context; providers need not support concurrent calls.
type Provider interface {
	Descriptor() Descriptor
	Extract(context.Context, File) ([]Record, []Diagnostic, error)
}

// NormalizeRecords validates untrusted provider output and assigns its
// protected path and source fields.
func NormalizeRecords(descriptor Descriptor, file File, records []Record) ([]Record, error) {
	if strings.TrimSpace(descriptor.ID) == "" {
		return nil, errors.New("extractor id is required")
	}
	if strings.TrimSpace(descriptor.Fingerprint) == "" {
		return nil, errors.New("extractor fingerprint is required")
	}
	if len(records) > MaxRecords {
		return nil, fmt.Errorf("extractor %q returned %d records; maximum is %d", descriptor.ID, len(records), MaxRecords)
	}
	lineCount := strings.Count(file.Content, "\n") + 1
	ids := map[string]bool{}
	for i := range records {
		record := &records[i]
		if record.ID != "" {
			if ids[record.ID] {
				return nil, fmt.Errorf("extractor %q returned duplicate record id %q", descriptor.ID, record.ID)
			}
			ids[record.ID] = true
		}
		if record.Source != "" {
			return nil, fmt.Errorf("extractor %q attempted to set protected source", descriptor.ID)
		}
		if record.Path != "" && record.Path != file.Path {
			return nil, fmt.Errorf("extractor %q record references another file %q", descriptor.ID, record.Path)
		}
		record.Path = file.Path
		record.Source = "plugin:" + descriptor.ID
		if record.Kind == "" || strings.TrimSpace(record.Text) == "" {
			return nil, fmt.Errorf("extractor %q record %d requires kind and text", descriptor.ID, i)
		}
		if record.StartLine < 1 || record.EndLine < record.StartLine || record.EndLine > lineCount {
			return nil, fmt.Errorf("extractor %q record %d has invalid line range", descriptor.ID, i)
		}
		if len(record.Text) > MaxRecordText {
			return nil, fmt.Errorf("extractor %q record %d text exceeds %d bytes", descriptor.ID, i, MaxRecordText)
		}
		if !utf8.ValidString(record.Text) {
			return nil, fmt.Errorf("extractor %q record %d text is not UTF-8", descriptor.ID, i)
		}
		metadata, err := json.Marshal(record.Metadata)
		if err != nil || len(metadata) > MaxMetadataBytes {
			return nil, fmt.Errorf("extractor %q record %d metadata is invalid or too large", descriptor.ID, i)
		}
	}
	return records, nil
}
