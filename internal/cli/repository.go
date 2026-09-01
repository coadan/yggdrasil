package cli

import (
	"context"
	"fmt"
	"path/filepath"

	"github.com/coadan/yggdrasil/extractor"
	commandextractor "github.com/coadan/yggdrasil/extractor/command"
	publicindex "github.com/coadan/yggdrasil/index"
	"github.com/coadan/yggdrasil/internal/config"
	"github.com/coadan/yggdrasil/internal/project"
)

type cliRepository struct {
	index      *publicindex.Repository
	embedding  *lazyEmbeddingProvider
	extractors []*commandextractor.Provider
}

func openCLIRepository(
	ctx context.Context,
	paths project.Paths,
	cfg config.Config,
) (*cliRepository, error) {
	providers := make([]extractor.Provider, 0, len(cfg.Plugins))
	commandProviders := make([]*commandextractor.Provider, 0, len(cfg.Plugins))
	for _, pluginConfig := range cfg.Plugins {
		provider, err := commandextractor.New(ctx, paths.Root, commandextractor.Spec{
			ID: pluginConfig.ID, Version: pluginConfig.Version,
			Command: pluginConfig.Command, IncludeGlobs: pluginConfig.IncludeGlobs,
			TimeoutMS: pluginConfig.TimeoutMS,
		})
		if err != nil {
			return nil, err
		}
		providers = append(providers, provider)
		commandProviders = append(commandProviders, provider)
	}
	capability, lazy := lazyCapability(paths.Root, cfg.Embedding)
	repository, err := publicindex.Open(publicindex.Options{
		Root:        paths.Root,
		StorageRoot: filepath.Dir(filepath.Dir(paths.StateDir)),
		IgnoreGlobs: cfg.IgnoreGlobs, MaxFileBytes: cfg.MaxFileBytes,
		Extractors: providers, Embedding: capability,
	})
	if err != nil {
		if lazy != nil {
			_ = lazy.Close()
		}
		for _, provider := range commandProviders {
			provider.Close()
		}
		return nil, err
	}
	return &cliRepository{
		index: repository, embedding: lazy, extractors: commandProviders,
	}, nil
}

func (r *cliRepository) Close() error {
	var result error
	if r.embedding != nil {
		result = r.embedding.Close()
	}
	for _, provider := range r.extractors {
		if diagnostics := provider.Close(); result == nil && len(diagnostics) > 0 {
			result = fmt.Errorf(
				"extractor %s did not close cleanly: %s",
				provider.Descriptor().ID, diagnostics[0].Message,
			)
		}
	}
	return result
}
