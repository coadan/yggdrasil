package embedding_test

import (
	"context"
	"testing"

	"github.com/coadan/yggdrasil/embedding"
)

type provider struct{}

func (provider) Embed(_ context.Context, inputs []embedding.Input) ([]embedding.Value, error) {
	values := make([]embedding.Value, len(inputs))
	for i, input := range inputs {
		values[i] = embedding.Value{ID: input.ID, Vector: []float32{1}}
	}
	return values, nil
}

func (provider) Close() error { return nil }

func TestProviderCanBeImplementedOutsideYggdrasil(t *testing.T) {
	var capability embedding.Provider = provider{}
	values, err := capability.Embed(context.Background(), []embedding.Input{{ID: "query", Text: "owner"}})
	if err != nil {
		t.Fatal(err)
	}
	if len(values) != 1 || values[0].ID != "query" || len(values[0].Vector) != 1 {
		t.Fatalf("values=%#v", values)
	}
}
