// Package engine provides optional long-lived composition for retrieval hosts.
package engine

import (
	"context"
	"errors"
	"sync"
	"time"
)

const MaxPriorityPaths = 100

// Demand contains mechanical hints for the next bounded refresh unit.
type Demand struct {
	Scope string
	Paths []string
	// Aging is set by the refresher when priority must be ignored for one unit.
	Aging bool
}

// Outcome is the observable result of one bounded refresh unit.
type Outcome struct {
	Phase    string
	Embedded int
	Records  int
	Complete bool
}

// RefreshFunc performs one cancellable, bounded unit of index work.
type RefreshFunc func(context.Context, Demand) (Outcome, error)

type RefresherOptions struct {
	Interval     time.Duration
	RetryBackoff time.Duration
	WorkTimeout  time.Duration
	// AgingEvery runs one unprioritized unit after this many total units.
	// It must be at least two.
	AgingEvery int
}

type Status struct {
	Running     bool
	Phase       string
	Embedded    int
	Records     int
	Complete    bool
	Runs        uint64
	Failures    uint64
	LastSuccess time.Time
	LastFailure time.Time
	LastError   string
}

// Refresher continuously advances an eventually consistent derived index.
// Constructing other engine values never starts it; StartRefresher is explicit.
type Refresher struct {
	cancel context.CancelFunc
	done   chan struct{}
	wake   chan struct{}

	mu      sync.RWMutex
	status  Status
	pending Demand
}

func StartRefresher(
	parent context.Context,
	opts RefresherOptions,
	refresh RefreshFunc,
) (*Refresher, error) {
	if refresh == nil {
		return nil, errors.New("refresh function is required")
	}
	if opts.Interval <= 0 {
		return nil, errors.New("refresh interval must be positive")
	}
	if opts.RetryBackoff <= 0 {
		return nil, errors.New("refresh retry backoff must be positive")
	}
	if opts.WorkTimeout <= 0 {
		return nil, errors.New("refresh work timeout must be positive")
	}
	if opts.AgingEvery < 2 {
		return nil, errors.New("refresh aging interval must be at least two units")
	}
	ctx, cancel := context.WithCancel(parent)
	value := &Refresher{
		cancel: cancel,
		done:   make(chan struct{}),
		wake:   make(chan struct{}, 1),
		status: Status{Running: true, Phase: "starting"},
	}
	go value.run(ctx, opts, refresh)
	return value, nil
}

// Wake records priority for a later refresh unit and returns immediately.
// Repeated calls coalesce exact paths and retain the most recent nonempty scope.
func (r *Refresher) Wake(demand Demand) {
	r.mu.Lock()
	if demand.Scope != "" {
		r.pending.Scope = demand.Scope
	}
	seen := make(map[string]bool, len(r.pending.Paths)+len(demand.Paths))
	for _, path := range r.pending.Paths {
		seen[path] = true
	}
	for _, path := range demand.Paths {
		if path == "" || seen[path] || len(r.pending.Paths) == MaxPriorityPaths {
			continue
		}
		r.pending.Paths = append(r.pending.Paths, path)
		seen[path] = true
	}
	r.mu.Unlock()
	select {
	case r.wake <- struct{}{}:
	default:
	}
}

func (r *Refresher) Status() Status {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.status
}

func (r *Refresher) Close() error {
	r.cancel()
	<-r.done
	return nil
}

func (r *Refresher) run(ctx context.Context, opts RefresherOptions, refresh RefreshFunc) {
	defer close(r.done)
	defer func() {
		r.mu.Lock()
		r.status.Running = false
		r.status.Phase = "stopped"
		r.mu.Unlock()
	}()
	first := true
	for {
		if !first {
			delay := opts.Interval
			r.mu.RLock()
			failed := r.status.LastError != ""
			r.mu.RUnlock()
			if failed {
				delay = opts.RetryBackoff
			}
			timer := time.NewTimer(delay)
			if failed {
				select {
				case <-ctx.Done():
					timer.Stop()
					return
				case <-timer.C:
				}
			} else {
				select {
				case <-ctx.Done():
					timer.Stop()
					return
				case <-r.wake:
					if !timer.Stop() {
						<-timer.C
					}
				case <-timer.C:
				}
			}
		}
		first = false
		select {
		case <-ctx.Done():
			return
		default:
		}
		demand, run := r.nextDemand(opts.AgingEvery)
		workCtx, cancel := context.WithTimeout(ctx, opts.WorkTimeout)
		outcome, err := refresh(workCtx, demand)
		cancel()
		r.record(run, outcome, err)
	}
}

func (r *Refresher) nextDemand(agingEvery int) (Demand, uint64) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.status.Runs++
	run := r.status.Runs
	demand := r.pending
	if run == 1 || run%uint64(agingEvery) == 0 {
		demand = Demand{Aging: true}
		if r.pending.Scope != "" || len(r.pending.Paths) > 0 {
			select {
			case r.wake <- struct{}{}:
			default:
			}
		}
	} else {
		r.pending = Demand{}
	}
	r.status.Phase = "refreshing"
	return demand, run
}

func (r *Refresher) record(run uint64, outcome Outcome, err error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.status.Runs != run {
		return
	}
	r.status.Embedded = outcome.Embedded
	r.status.Records = outcome.Records
	r.status.Complete = outcome.Complete
	if err != nil {
		r.status.Failures++
		r.status.Phase = "degraded"
		r.status.LastFailure = time.Now()
		r.status.LastError = err.Error()
		return
	}
	r.status.Phase = outcome.Phase
	if r.status.Phase == "" {
		r.status.Phase = "idle"
	}
	r.status.LastSuccess = time.Now()
	r.status.LastError = ""
}
