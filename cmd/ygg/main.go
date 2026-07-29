package main

import (
	"context"
	"os"

	"github.com/coadan/yggdrasil/internal/cli"
)

func main() {
	os.Exit(cli.Main(context.Background(), os.Args[1:], os.Stdout, os.Stderr))
}
