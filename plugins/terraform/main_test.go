package main

import (
	"strings"
	"testing"
)

func TestExtractsBlocksAttributesAndReferences(t *testing.T) {
	records, diagnostics := extract("main.tf", `variable "endpoint_type" {
  type = string
}

resource "aws_vpc_endpoint" "this" {
  vpc_id = var.vpc_id

  dns_options {
    dns_record_ip_type = var.endpoint_type
  }
}
`)
	if len(diagnostics) != 0 {
		t.Fatal(diagnostics)
	}
	facts := map[string]record{}
	for _, value := range records {
		facts[value.Kind+":"+value.Title] = value
	}
	if facts["terraform-block:variable.endpoint_type"].StartLine != 1 ||
		facts["terraform-block:resource.aws_vpc_endpoint.this"].StartLine != 5 ||
		facts["terraform-block:resource.aws_vpc_endpoint.this.dns_options"].StartLine != 8 {
		t.Fatalf("blocks=%#v", records)
	}
	attribute := facts["terraform-attribute:resource.aws_vpc_endpoint.this.dns_options.dns_record_ip_type"]
	if attribute.StartLine != 9 ||
		!strings.Contains(attribute.Text, "dns record ip type") ||
		attribute.Metadata["references"].([]string)[0] != "var.endpoint_type" {
		t.Fatalf("attribute=%#v", attribute)
	}
}

func TestReportsParserDiagnostics(t *testing.T) {
	_, diagnostics := extract("broken.tf", `resource "x" "y" {`)
	if len(diagnostics) == 0 {
		t.Fatal("expected diagnostic")
	}
}
