// Package command adapts bounded JSONL extractor subprocesses to the public
// extractor capability.
package command

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"

	"github.com/coadan/yggdrasil/extractor"
	"github.com/coadan/yggdrasil/internal/config"
	"github.com/coadan/yggdrasil/internal/discovery"
	"github.com/coadan/yggdrasil/internal/plugin"
)

type Spec struct {
	ID           string
	Version      string
	Command      []string
	IncludeGlobs []string
	TimeoutMS    int
}

type Provider struct {
	descriptor extractor.Descriptor
	manager    *plugin.Manager
}

// New constructs one lazy provider. Its subprocess starts only when a matching file
// is extracted.
func New(ctx context.Context, root string, spec Spec) (*Provider, error) {
	pluginConfig := config.Plugin{
		ID: spec.ID, Version: spec.Version,
		Command:      append([]string(nil), spec.Command...),
		IncludeGlobs: append([]string(nil), spec.IncludeGlobs...),
		TimeoutMS:    spec.TimeoutMS,
	}
	validation := config.Default()
	validation.Plugins = []config.Plugin{pluginConfig}
	if err := validation.Validate(); err != nil {
		return nil, err
	}
	payload, _ := json.Marshal(spec)
	sum := sha256.Sum256(payload)
	return &Provider{
		descriptor: extractor.Descriptor{
			ID: spec.ID, Fingerprint: "sha256:" + hex.EncodeToString(sum[:]),
		},
		manager: plugin.NewManager(ctx, root, []config.Plugin{pluginConfig}),
	}, nil
}

func (p *Provider) Descriptor() extractor.Descriptor { return p.descriptor }

func (p *Provider) Extract(
	_ context.Context,
	file extractor.File,
) ([]extractor.Record, []extractor.Diagnostic, error) {
	records, diagnostics := p.manager.Extract(discovery.File{
		Candidate: discovery.Candidate{Path: file.Path, Size: int64(len(file.Content))},
		Kind:      file.Kind, Content: file.Content, ContentHash: file.ContentHash,
	})
	for index := range records {
		// The public index boundary validates and restores these protected
		// fields for this provider identity.
		records[index].Path = ""
		records[index].Source = ""
	}
	resultDiagnostics := make([]extractor.Diagnostic, len(diagnostics))
	for index, diagnostic := range diagnostics {
		resultDiagnostics[index] = extractor.Diagnostic{
			Path: diagnostic.Path, Plugin: diagnostic.Plugin,
			Stage: diagnostic.Stage, Message: diagnostic.Message,
		}
	}
	return records, resultDiagnostics, nil
}

func (p *Provider) Close() []extractor.Diagnostic {
	diagnostics := p.manager.Close()
	result := make([]extractor.Diagnostic, len(diagnostics))
	for index, diagnostic := range diagnostics {
		result[index] = extractor.Diagnostic{
			Path: diagnostic.Path, Plugin: diagnostic.Plugin,
			Stage: diagnostic.Stage, Message: diagnostic.Message,
		}
	}
	return result
}
