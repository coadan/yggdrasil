package plugin

import (
	"context"
	"path/filepath"
	"runtime"
	"testing"

	"github.com/coadan/yggdrasil/internal/config"
	"github.com/coadan/yggdrasil/internal/discovery"
)

func TestPythonReferenceAdapterProtocol(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("reference adapter uses a Python shebang")
	}
	command, err := filepath.Abs(filepath.Join("..", "..", "plugins", "python", "ygg-extract-python"))
	if err != nil {
		t.Fatal(err)
	}
	file := discovery.File{
		Candidate: discovery.Candidate{Path: "service.py"},
		Kind:      "py",
		Content:   "from .models import Panel\n\nclass PanelService:\n    def load_panel(self):\n        return Panel()\n",
	}
	// Use the normal provider budget: this checks the protocol rather than
	// startup latency while other packages initialize concurrently.
	result, err := Check(context.Background(), t.TempDir(), config.Plugin{
		ID: "python", Version: "1", Command: []string{command},
		IncludeGlobs: []string{"**/*.py"}, TimeoutMS: config.DefaultTimeoutMS,
	}, &file)
	if err != nil {
		t.Fatal(err)
	}
	if !result.Ready || len(result.Diagnostics) != 0 {
		t.Fatalf("result=%#v", result)
	}
	facts := map[string]bool{}
	for _, record := range result.Records {
		facts[record.Kind+":"+record.Title] = true
		if record.Path != "service.py" || record.Source != "plugin:python" {
			t.Fatalf("record=%#v", record)
		}
	}
	for _, expected := range []string{
		"python-import:.models",
		"python-class:PanelService",
		"python-method:PanelService.load_panel",
	} {
		if !facts[expected] {
			t.Fatalf("missing %s in %#v", expected, result.Records)
		}
	}
}
