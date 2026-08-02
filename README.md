# kotoba-lang/text

[![CI](https://github.com/kotoba-lang/text/actions/workflows/ci.yml/badge.svg)](https://github.com/kotoba-lang/text/actions/workflows/ci.yml)

**String / regex / unicode helpers** — the foundational-stdlib gap every other
lib re-rolls (`json`, `lint`, `time` hand-roll string ops). Zero third-party
deps; every namespace is `.cljc` (JVM / SCI / ClojureScript / GraalVM /
kotoba-WASM). Regex uses the host's `#"...";` pure string ops are portable. See
[`docs/adr/ADR-kotoba-lang-foundational-stdlib.md`](https://github.com/kotoba-lang/kotoba-lang/blob/main/docs/adr/ADR-kotoba-lang-foundational-stdlib.md).

## Current surface

`kotoba.lang.text`:

- `split` / `split-lines` / `join` — tokenize / assemble
- `trim` / `triml` / `trimr` — whitespace trimming
- `upper` / `lower` / `capitalize` — case
- `starts-with?` / `ends-with?` / `includes?` — predicates
- `replace` / `replace-first` — regex substitution
- `re-find` / `re-matches` / `re-seq` — host regex (thin, portable wrappers)
- `format` — printf-style (`%s %d`) via a pure impl (no `String/format` — WASM-safe)
- `codepoints` / `from-codepoints` — unicode codepoint seqs (portable)
- `pad-left` / `pad-right` — padding
- `truncate` — truncate with optional ellipsis

`kotoba.lang.bounded-text` is the sovereign `.kotoba` kernel for the bounded
portable subset used during CLJC migration: contains, starts/ends-with,
replace-all, and case-fold. The CLJC namespace remains the
general oracle for regex, collection-returning split, Unicode construction,
formatting, trim, and padding until those surfaces have bounded compiler
contracts. See `migration/bounded-text-v1.edn`.

## Install

```clojure
io.github.kotoba-lang/text {:git/sha "<sha>"}
```

## Use

```clojure
(require '[kotoba.lang.text :as text])

(text/split "a,b,c" #",")            ;=> ["a" "b" "c"]
(text/trim "  hi  ")                 ;=> "hi"
(text/capitalize "hello")            ;=> "Hello"
(text/format "%s = %d" ["x" 42])     ;=> "x = 42"
(text/codepoints "ab")               ;=> [97 98]
(text/truncate "hello world" 8 "..."));=> "hello..."
```

## Verify

```sh
clojure -M:test
```
