package discovery

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const testMaxBytes = int64(1024)

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

func TestReadSkipsSecretMaterial(t *testing.T) {
	root := t.TempDir()
	for _, name := range []string{"client.key", "certificate.pem", "root.crt", "chain.cer", "chain.cert"} {
		content := []byte("private material")
		if err := os.WriteFile(filepath.Join(root, name), content, 0o600); err != nil {
			t.Fatal(err)
		}
		file, skipped, err := Read(root, Candidate{Path: name, Size: int64(len(content))}, testMaxBytes)
		if err != nil || skipped == nil || skipped.Reason != "secret-material" || file.Path != "" {
			t.Fatalf("%s file=%#v skipped=%#v err=%v", name, file, skipped, err)
		}
	}
}

func TestReadSanitizesDotenvValues(t *testing.T) {
	root := t.TempDir()
	content := "PUBLIC_NAME=panels\nexport API_TOKEN='never-index-this'\n# secret comment\nnot an assignment\n"
	name := ".env.example"
	if err := os.WriteFile(filepath.Join(root, name), []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	file, skipped, err := Read(root, Candidate{Path: name, Size: int64(len(content))}, testMaxBytes)
	if err != nil || skipped != nil {
		t.Fatalf("file=%#v skipped=%#v err=%v", file, skipped, err)
	}
	if file.Kind != "env" ||
		!strings.Contains(file.Content, "PUBLIC_NAME=<redacted>") ||
		!strings.Contains(file.Content, "export API_TOKEN=<redacted>") ||
		strings.Contains(file.Content, "panels") ||
		strings.Contains(file.Content, "never-index-this") ||
		strings.Contains(file.Content, "secret comment") ||
		strings.Count(file.Content, "\n") != strings.Count(content, "\n") {
		t.Fatalf("content=%q", file.Content)
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

func TestDiscoveryDoesNotFollowRepositorySymlinks(t *testing.T) {
	root := t.TempDir()
	outside := filepath.Join(t.TempDir(), "outside.txt")
	if err := os.WriteFile(outside, []byte("secret"), 0o644); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(root, "outside.txt")
	if err := os.Symlink(outside, link); err != nil {
		t.Fatal(err)
	}
	candidates, err := Candidates(root, nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(candidates) != 0 {
		t.Fatalf("symlink became candidate: %#v", candidates)
	}
	_, skipped, err := Read(root, Candidate{
		Path: "outside.txt", Size: int64(len("secret")), MTimeNS: 1,
	}, 1024)
	if err != nil || skipped == nil || skipped.Reason != "non-regular" {
		t.Fatalf("skipped=%#v err=%v", skipped, err)
	}
}
