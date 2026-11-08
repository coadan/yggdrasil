package main

import (
	"bytes"
	"encoding/json"
	"strings"
	"testing"
)

func facts(t *testing.T, file, content string) map[string]record {
	t.Helper()
	got := make(map[string]record)
	for _, value := range extract(file, content) {
		got[value.Kind+":"+value.Title] = value
	}
	return got
}

func TestExtractsItemsMethodsAndSpans(t *testing.T) {
	content := `//! Crate docs mention fn NotAnItem.
use std::collections::HashMap;
use crate::{config::Config, search::Searcher as S};
extern crate regex;

mod printer;
pub(crate) mod walk;

#[derive(Debug)]
pub struct LineBuffer<'a> {
    data: &'a [u8; 4],
}

impl<'a, T: Clone> Default for LineBuffer<'a> where T: Send {
    fn default() -> Self {
        let c = '{';
        let s = "fn hidden() {";
        todo!()
    }
}

impl<'a> LineBuffer<'a> {
    pub const LIMIT: usize = 8;
    pub(crate) async fn fill_buffer(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

pub trait Sink {
    fn matched(&mut self, line: &[u8]) -> bool;
    type Error;
}

pub enum Mode { Fast, Slow }
const fn helper() -> u8 { 1 }
static mut COUNTER: u32 = 0;
macro_rules! debug_line { () => {}; }
pub type Result<T> = std::result::Result<T, Error>;
`
	got := facts(t, "crates/core/lib.rs", content)
	want := map[string][2]int{
		"rust-struct:LineBuffer":              {10, 12},
		"rust-impl:Default for LineBuffer":    {14, 20},
		"rust-method:LineBuffer::default":     {15, 19},
		"rust-impl:LineBuffer":                {22, 27},
		"rust-assoc-const:LineBuffer::LIMIT":  {23, 23},
		"rust-method:LineBuffer::fill_buffer": {24, 26},
		"rust-trait:Sink":                     {29, 32},
		"rust-method:Sink::matched":           {30, 30},
		"rust-assoc-type:Sink::Error":         {31, 31},
		"rust-enum:Mode":                      {34, 34},
		"rust-fn:helper":                      {35, 35},
		"rust-static:COUNTER":                 {36, 36},
		"rust-macro:debug_line":               {37, 37},
		"rust-type:Result":                    {38, 38},
		"rust-import:printer":                 {6, 6},
		"rust-import:walk":                    {7, 7},
		"rust-use:std::collections::HashMap":  {2, 2},
		"rust-use:regex":                      {4, 4},
	}
	for key, span := range want {
		value, ok := got[key]
		if !ok {
			t.Errorf("missing %s; got %v", key, keys(got))
			continue
		}
		if value.StartLine != span[0] || value.EndLine != span[1] {
			t.Errorf("%s span %d-%d, want %d-%d", key, value.StartLine, value.EndLine, span[0], span[1])
		}
	}
	for key := range got {
		if strings.Contains(key, "hidden") || strings.Contains(key, "NotAnItem") || strings.HasSuffix(key, ":c") {
			t.Errorf("leaked %s", key)
		}
	}
	if _, ok := got["rust-use:crate::{config::Config,search::Searcher as S}"]; !ok {
		t.Errorf("use alias: %v", keys(got))
	}
	if !strings.Contains(got["rust-method:LineBuffer::fill_buffer"].Text, "fill buffer") {
		t.Errorf("identifier words missing: %q", got["rust-method:LineBuffer::fill_buffer"].Text)
	}
	if !strings.Contains(got["rust-navigation:crates/core/lib.rs"].Text, "rust-trait Sink") {
		t.Errorf("navigation: %#v", got["rust-navigation:crates/core/lib.rs"])
	}
}

func TestScannerSkipsRawStringsNestedCommentsAndCharLiterals(t *testing.T) {
	content := `/* outer /* inner fn Bad() {} */ still comment { */
const RAW: &str = r#"fn AlsoBad() { "quoted" }"#;
const BYTES: &[u8] = br"{";
const QUOTE: char = '\'';
const BRACE: char = '}';
fn generic<'a>(x: &'a str) -> &'a str { x }
fn after() {}
`
	got := facts(t, "src/main.rs", content)
	for _, key := range []string{"rust-const:RAW", "rust-const:BYTES", "rust-const:QUOTE", "rust-const:BRACE",
		"rust-fn:generic", "rust-fn:after"} {
		if _, ok := got[key]; !ok {
			t.Errorf("missing %s; got %v", key, keys(got))
		}
	}
	if len(got) != 7 {
		t.Errorf("got %v", keys(got))
	}
	if got["rust-fn:after"].StartLine != 7 {
		t.Errorf("line drift: %#v", got["rust-fn:after"])
	}
}

func TestInlineModulesAndPathAttributes(t *testing.T) {
	content := `mod outer {
    pub fn inside() {}
    mod nested;
}
#[path = "platform/unix.rs"]
mod sys;
`
	got := facts(t, "src/lib.rs", content)
	for _, key := range []string{"rust-mod:outer", "rust-fn:inside", "rust-import:outer/nested", "rust-import:./platform/unix.rs"} {
		if _, ok := got[key]; !ok {
			t.Errorf("missing %s; got %v", key, keys(got))
		}
	}
	if got["rust-mod:outer"].EndLine != 4 {
		t.Errorf("mod span %#v", got["rust-mod:outer"])
	}
}

func TestProtocolRoundTrip(t *testing.T) {
	input := strings.Join([]string{
		`{"type":"hello","schema":"ygg.extractor/v1"}`,
		`{"type":"file","requestId":"1","file":{"path":"a.rs","content":"fn main() {}\n"}}`,
		`{"type":"end"}`,
	}, "\n")
	var output bytes.Buffer
	if err := run(strings.NewReader(input), &output); err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(strings.TrimSpace(output.String()), "\n")
	if len(lines) != 3 {
		t.Fatalf("output %q", output.String())
	}
	var result struct {
		Records []record `json:"records"`
	}
	if err := json.Unmarshal([]byte(lines[1]), &result); err != nil || len(result.Records) != 2 {
		t.Fatalf("result %q err %v", lines[1], err)
	}
}

func keys(values map[string]record) []string {
	var result []string
	for key := range values {
		result = append(result, key)
	}
	return result
}
