package main

import (
	"strings"
	"testing"
)

func TestExtractsGoDeclarationsAndImports(t *testing.T) {
	content := `package sample

import "context"

type RouteAccess struct{}
const DefaultMode = "safe"

func BuildRouteAccess(ctx context.Context) RouteAccess {
	return RouteAccess{}
}

func (RouteAccess) Allowed() bool { return true }
`
	records, diagnostics := extract("sample.go", content)
	if len(diagnostics) != 0 {
		t.Fatalf("diagnostics=%#v", diagnostics)
	}
	if len(records) != 5 {
		t.Fatalf("records=%#v", records)
	}
	got := make(map[string]record)
	for _, value := range records {
		got[value.Kind+":"+value.Title] = value
	}
	if got["go-import:context"].StartLine != 3 ||
		!strings.Contains(got["go-function:BuildRouteAccess"].Text, "build route access") ||
		got["go-method:Allowed"].EndLine < got["go-method:Allowed"].StartLine {
		t.Fatalf("records=%#v", records)
	}
}

func TestReturnsPartialRecordsWithSyntaxDiagnostic(t *testing.T) {
	records, diagnostics := extract("broken.go", "package p\nfunc Ready() {}\nfunc Broken(")
	if len(records) == 0 || len(diagnostics) != 1 {
		t.Fatalf("records=%#v diagnostics=%#v", records, diagnostics)
	}
}
