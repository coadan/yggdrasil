package engine

import (
	"context"
	"errors"
	"fmt"

	"github.com/coadan/yggdrasil/index"
	"github.com/coadan/yggdrasil/query"
)

type ServiceOptions struct {
	Refresher           RefresherOptions
	MinSemanticCoverage float64
}

// Service composes continuously refreshed indexing with immediate search.
type Service struct {
	repository          *index.Repository
	refresher           *Refresher
	minSemanticCoverage float64
}

func StartService(
	parent context.Context,
	repository *index.Repository,
	opts ServiceOptions,
) (*Service, error) {
	if repository == nil {
		return nil, errors.New("repository index is required")
	}
	coverage := opts.MinSemanticCoverage
	if coverage == 0 {
		coverage = 1
	}
	if coverage <= 0 || coverage > 1 {
		return nil, errors.New("minimum semantic coverage must be greater than zero and at most one")
	}
	refresher, err := StartIndexRefresher(parent, opts.Refresher, repository)
	if err != nil {
		return nil, err
	}
	return &Service{
		repository: repository, refresher: refresher, minSemanticCoverage: coverage,
	}, nil
}

func (s *Service) Close() error   { return s.refresher.Close() }
func (s *Service) Status() Status { return s.refresher.Status() }

// Search reads one current snapshot or immediately uses live filesystem
// evidence. It never waits for the refresher or semantic coverage.
func (s *Service) Search(
	ctx context.Context,
	pattern string,
	opts query.Options,
) (query.Result, error) {
	if opts.Scope == "" {
		opts.Scope = s.repository.Scope()
	}
	if opts.Embedding == nil {
		opts.Embedding = s.repository.Embedding()
	}
	if !opts.HasExtractors {
		opts.HasExtractors = s.repository.HasExtractor()
	}
	if opts.MinSemanticCoverage == 0 {
		opts.MinSemanticCoverage = s.minSemanticCoverage
	}
	snapshot, err := s.repository.OpenSnapshot(ctx)
	if err == nil {
		defer snapshot.Close()
		result, searchErr := query.Run(ctx, snapshot, pattern, opts)
		if index.IsSnapshotUnavailable(searchErr) {
			return s.searchFilesystem(ctx, pattern, opts, "index-busy")
		}
		if searchErr == nil {
			s.prioritize(result)
		}
		return result, searchErr
	}
	if !index.IsSnapshotUnavailable(err) {
		return query.Result{}, err
	}
	mode := opts.Mode
	if mode == "" {
		mode = "auto"
	}
	if mode == "semantic" || mode == "graph" {
		return query.Result{}, fmt.Errorf("%w: current index snapshot is unavailable", query.ErrSemanticUnavailable)
	}
	reason := "index-unavailable"
	if errors.Is(err, index.ErrStale) {
		reason = "index-stale"
	}
	return s.searchFilesystem(ctx, pattern, opts, reason)
}

func (s *Service) searchFilesystem(
	ctx context.Context,
	pattern string,
	opts query.Options,
	reason string,
) (query.Result, error) {
	mode := opts.Mode
	if mode == "" {
		mode = "auto"
	}
	result, searchErr := query.RunFilesystem(ctx, s.repository.Root(), pattern, query.FilesystemOptions{
		Limit: opts.Limit, Scope: opts.Scope,
		IgnoreGlobs: s.repository.IgnoreGlobs(), MaxFileBytes: s.repository.MaxFileBytes(),
		RequestedMode: mode, FallbackReason: reason,
		MatchKind: opts.MatchKind, About: opts.About,
	})
	if searchErr == nil {
		s.prioritize(result)
	}
	return result, searchErr
}

func (s *Service) prioritize(result query.Result) {
	paths := make([]string, 0, len(result.Records)+len(result.MorePaths))
	for _, record := range result.Records {
		paths = append(paths, record.Path)
	}
	paths = append(paths, result.MorePaths...)
	s.refresher.Wake(Demand{Scope: result.QueryPlan.Scope, Paths: paths})
}
