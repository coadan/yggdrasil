package engine

import (
	"context"

	"github.com/coadan/yggdrasil/index"
)

// Repository is the synchronous capability consumed by the optional
// refresher. Implementations perform exactly one bounded unit per call.
type Repository interface {
	Refresh(context.Context, index.RefreshOptions) (index.RefreshResult, error)
}

// StartIndexRefresher explicitly starts continuous bounded work for a
// repository index. Search remains a separate operation and never joins it.
func StartIndexRefresher(
	parent context.Context,
	opts RefresherOptions,
	repository Repository,
) (*Refresher, error) {
	return StartRefresher(parent, opts, func(ctx context.Context, demand Demand) (Outcome, error) {
		result, err := repository.Refresh(ctx, index.RefreshOptions{
			PriorityScope:    demand.Scope,
			PriorityPaths:    demand.Paths,
			Aging:            demand.Aging,
			EmbeddingBatches: 1,
		})
		if err != nil {
			return Outcome{}, err
		}
		phase := "indexing"
		if result.Coverage.Complete || result.EmbeddingStatus == "unconfigured" {
			phase = "idle"
		}
		return Outcome{
			Phase: phase, Embedded: result.Coverage.Embedded,
			Records: result.Coverage.Records, Complete: result.Coverage.Complete,
		}, nil
	})
}
