package engine

import (
	"context"
	"errors"
	"reflect"
	"testing"
	"time"
)

func testOptions() RefresherOptions {
	return RefresherOptions{
		Interval: time.Hour, RetryBackoff: time.Hour,
		WorkTimeout: time.Second, AgingEvery: 3,
	}
}

func TestRefresherStartsExplicitlyAndCoalescesDemand(t *testing.T) {
	requests := make(chan Demand, 3)
	releaseInitial := make(chan struct{})
	calls := 0
	value, err := StartRefresher(context.Background(), testOptions(), func(_ context.Context, demand Demand) (Outcome, error) {
		calls++
		requests <- demand
		if calls == 1 {
			<-releaseInitial
		}
		return Outcome{Phase: "idle", Embedded: 1, Records: 2}, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	if first := <-requests; !first.Aging {
		t.Fatalf("initial demand=%#v", first)
	}
	value.Wake(Demand{Scope: "src/", Paths: []string{"a.go", "shared.go", "a.go"}})
	value.Wake(Demand{Paths: []string{"b.go", "shared.go"}})
	close(releaseInitial)
	select {
	case demand := <-requests:
		if demand.Aging || demand.Scope != "src/" ||
			!reflect.DeepEqual(demand.Paths, []string{"b.go", "shared.go", "a.go"}) {
			t.Fatalf("demand=%#v", demand)
		}
	case <-time.After(time.Second):
		t.Fatal("refresher did not wake")
	}
	deadline := time.Now().Add(time.Second)
	for {
		status := value.Status()
		if status.Running && status.Runs == 2 && status.Embedded == 1 && status.Records == 2 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("status=%#v", status)
		}
		time.Sleep(time.Millisecond)
	}
}

func TestRefresherRunsDeterministicAgingUnit(t *testing.T) {
	requests := make(chan Demand, 3)
	value, err := StartRefresher(context.Background(), testOptions(), func(_ context.Context, demand Demand) (Outcome, error) {
		requests <- demand
		return Outcome{Phase: "idle"}, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	defer value.Close()
	<-requests
	value.Wake(Demand{Scope: "src/"})
	<-requests
	value.Wake(Demand{Scope: "docs/", Paths: []string{"docs/a.md"}})
	third := <-requests
	if !third.Aging || third.Scope != "" || len(third.Paths) != 0 {
		t.Fatalf("third demand=%#v", third)
	}
}

func TestRefresherBacksOffAfterFailureAndCloseCancelsWork(t *testing.T) {
	started := make(chan int, 2)
	attempt := 0
	opts := testOptions()
	opts.RetryBackoff = 30 * time.Millisecond
	value, err := StartRefresher(context.Background(), opts, func(ctx context.Context, _ Demand) (Outcome, error) {
		attempt++
		started <- attempt
		if attempt == 1 {
			return Outcome{}, errors.New("temporary")
		}
		<-ctx.Done()
		return Outcome{}, ctx.Err()
	})
	if err != nil {
		t.Fatal(err)
	}
	if got := <-started; got != 1 {
		t.Fatalf("attempt=%d", got)
	}
	value.Wake(Demand{Scope: "src/"})
	select {
	case <-started:
		t.Fatal("wake bypassed retry backoff")
	case <-time.After(10 * time.Millisecond):
	}
	select {
	case got := <-started:
		if got != 2 {
			t.Fatalf("attempt=%d", got)
		}
	case <-time.After(time.Second):
		t.Fatal("retry did not run")
	}
	if err := value.Close(); err != nil {
		t.Fatal(err)
	}
	status := value.Status()
	if status.Running || status.Phase != "stopped" || status.Failures == 0 {
		t.Fatalf("status=%#v", status)
	}
}
