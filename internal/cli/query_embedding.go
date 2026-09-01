package cli

import (
	"context"

	embeddingcontract "github.com/coadan/yggdrasil/embedding"
	"github.com/coadan/yggdrasil/internal/config"
	internalembedding "github.com/coadan/yggdrasil/internal/embedding"
)

// lazyEmbeddingProvider preserves the CLI rule that provider startup occurs
// only after semantic readiness crosses the activation gate.
type lazyEmbeddingProvider struct {
	root     string
	config   config.Embedding
	provider embeddingcontract.Provider
}

func lazyCapability(
	root string,
	cfg *config.Embedding,
) (*embeddingcontract.Capability, *lazyEmbeddingProvider) {
	if cfg == nil {
		return nil, nil
	}
	lazy := &lazyEmbeddingProvider{root: root, config: *cfg}
	return &embeddingcontract.Capability{
		Provider: lazy, ProviderFingerprint: "cli-compatibility",
		IndexFingerprint: internalembedding.Fingerprint(*cfg),
		Model:            cfg.Model, Dimensions: cfg.Dimensions,
		QueryPrefix: cfg.QueryPrefix, DocumentPrefix: cfg.DocumentPrefix,
		BatchSize: cfg.BatchSize, MaxInputChars: cfg.MaxInputChars,
	}, lazy
}

func (p *lazyEmbeddingProvider) Embed(
	ctx context.Context,
	inputs []embeddingcontract.Input,
) ([]embeddingcontract.Value, error) {
	if p.provider == nil {
		provider, err := internalembedding.New(ctx, p.root, p.config)
		if err != nil {
			return nil, err
		}
		p.provider = provider
	}
	return p.provider.Embed(ctx, inputs)
}

func (p *lazyEmbeddingProvider) Close() error {
	if p.provider == nil {
		return nil
	}
	return p.provider.Close()
}
