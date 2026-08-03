package main

import (
	"strings"
	"testing"
)

func TestExtractsTopLevelDeclarationsAndImports(t *testing.T) {
	content := `import type { RouteState } from "./route-state";
export { routeAccess } from './route-access';

export interface WorldMapAccess {
  allowed: boolean;
}

export const buildRouteAccess = () => true;
function actorRelativeRoute() {
  const nestedValue = 1;
  return nestedValue;
}
`
	records := extract("src/routes.ts", content)
	if len(records) != 8 {
		t.Fatalf("records=%#v", records)
	}
	got := make(map[string]record)
	for _, value := range records {
		got[value.Kind+":"+value.Title] = value
	}
	if got["typescript-import:./route-state"].StartLine != 1 ||
		got["typescript-export:./route-access"].StartLine != 2 ||
		!strings.Contains(got["typescript-interface:WorldMapAccess"].Text, "world map access") ||
		!strings.Contains(got["typescript-const:buildRouteAccess"].Text, "build route access") ||
		strings.Contains(got["typescript-function:actorRelativeRoute"].Text, "return nestedValue") ||
		!strings.Contains(got["typescript-structural:actorRelativeRoute"].Text, "return nestedValue") ||
		!strings.Contains(got["typescript-navigation:src/routes.ts"].Text, "typescript-interface WorldMapAccess") {
		t.Fatalf("records=%#v", records)
	}
	if _, exists := got["typescript-const:nestedValue"]; exists {
		t.Fatalf("nested declaration leaked: %#v", records)
	}
}

func TestScannerIgnoresCommentAndStringKeywords(t *testing.T) {
	records := extract("input.ts", `// function NotADeclaration() {}
const text = "class AlsoNotADeclaration {}";
export class RealDeclaration {}
`)
	if len(records) != 3 || records[1].Title != "text" || records[2].Title != "RealDeclaration" {
		t.Fatalf("records=%#v", records)
	}
}
