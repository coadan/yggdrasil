package cli

import (
	"context"

	flowapi "github.com/example/breyta/flows"
)

type Client struct{}

const FlowMode = "flow"

func RunFlow(ctx context.Context) error {
	helper()
	return flowapi.Start(ctx)
}

func helper() {}

func (c *Client) PublishFlow(ctx context.Context) error {
	return RunFlow(ctx)
}
