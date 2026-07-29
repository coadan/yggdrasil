package discovery

import (
	"os"
	"path/filepath"
	"testing"
)

func TestMatchSupportsDoubleStar(t *testing.T) {
	for _, test := range []struct {
		pattern string
		path    string
		want    bool
	}{
		{"**/*.md", "docs/guide.md", true},
		{"**/*.md", "README.md", true},
		{"src/*.go", "src/main.go", true},
		{"src/*.go", "src/nested/main.go", false},
	} {
		if got := Match(test.pattern, test.path); got != test.want {
			t.Errorf("Match(%q, %q) = %v, want %v", test.pattern, test.path, got, test.want)
		}
	}
}

func TestCandidatesAndReadText(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "a.txt"), []byte("hello\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "skip.bin"), []byte{0, 1}, 0o644); err != nil {
		t.Fatal(err)
	}
	candidates, err := Candidates(root, nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(candidates) != 2 {
		t.Fatalf("candidates = %#v", candidates)
	}
	file, skipped, err := Read(root, candidates[0], 1024)
	if err != nil || skipped != nil || file.Content != "hello\n" {
		t.Fatalf("file=%#v skipped=%#v err=%v", file, skipped, err)
	}
	_, skipped, err = Read(root, candidates[1], 1024)
	if err != nil || skipped == nil || skipped.Reason != "non-text" {
		t.Fatalf("skipped=%#v err=%v", skipped, err)
	}
}
