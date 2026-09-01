// Package model contains storage-neutral values shared by query readers.
package model

type Candidate struct {
	ID        int64
	InputHash string
	Path      string
	StartLine int
	EndLine   int
	Kind      string
	Title     string
	Text      string
	Metadata  map[string]any
	Source    string
}

type EmbeddingState struct {
	Configured  bool   `json:"configured"`
	Fingerprint string `json:"fingerprint,omitempty"`
	Model       string `json:"model,omitempty"`
	Dimensions  int    `json:"dimensions,omitempty"`
	Embedded    int    `json:"embedded"`
	Records     int    `json:"records"`
	Complete    bool   `json:"complete"`
}
