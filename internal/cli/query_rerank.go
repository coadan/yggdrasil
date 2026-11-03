package cli

import (
	"context"

	"github.com/coadan/yggdrasil/internal/config"
	internalrerank "github.com/coadan/yggdrasil/internal/rerank"
	rerankcontract "github.com/coadan/yggdrasil/rerank"
)

// lazyRerankProvider starts the configured command only when a search
// actually has results to rescore.
type lazyRerankProvider struct {
	root     string
	config   config.Rerank
	provider rerankcontract.Provider
}

func lazyRerankCapability(root string, cfg *config.Rerank) (*rerankcontract.Capability, *lazyRerankProvider) {
	if cfg == nil {
		return nil, nil
	}
	lazy := &lazyRerankProvider{root: root, config: *cfg}
	return &rerankcontract.Capability{
		Provider: lazy, Depth: cfg.Depth, MaxInputChars: cfg.MaxInputChars,
	}, lazy
}

func (p *lazyRerankProvider) Rerank(
	ctx context.Context,
	query string,
	inputs []rerankcontract.Input,
) ([]rerankcontract.Score, error) {
	if p.provider == nil {
		provider, err := internalrerank.New(ctx, p.root, p.config)
		if err != nil {
			return nil, err
		}
		p.provider = provider
	}
	return p.provider.Rerank(ctx, query, inputs)
}

func (p *lazyRerankProvider) Close() error {
	if p.provider == nil {
		return nil
	}
	return p.provider.Close()
}
