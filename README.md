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
- `blank?` / `index-of` / `last-index-of` / `reverse` / `trim-newline` — the
  last clojure.string primitives callers had to reach for `clojure.string` for
- kernel oracles — `trim-text` / `triml-text` / `trimr-text` / `blank-text?` /
  `reverse-text` / `repeat-text` / `index-of-text` / `last-index-of-text` /
  `pad-left-text` / `pad-right-text`: the CLJC oracle for the same operations
  as the `.kotoba` kernel, with the divergences the kernel names (UTF-8 byte
  offsets, ASCII whitespace class, code-point-safe reversal)
- tranche-2 oracles — `replace-first-text` (literal match, not regex) /
  `trim-newline-text` / `segment-text` / `segment-count-text` (the
  slice-and-count face of a separator; no collection return needed) /
  `pad-center-text`

`kotoba.lang.bounded-text` is the sovereign `.kotoba` kernel for the bounded
portable subset used during CLJC migration: contains, starts/ends-with,
replace-all, case-fold, (2026-09-04) trim/triml/trimr, blank?,
reverse, repeat, index-of/last-index-of, both pads, replace-first,
trim-newline, segment-text/segment-count-text, and pad-center — each composed
from the language's string builtins (`string-code-point-at`,
`string-substring`, `string-concat`, …), no new compiler operation.
join-text is deferred with a measured reason (no readable string collection
on wasm32; see the migration record). The CLJC namespace remains the general
oracle for regex, collection-returning split, Unicode construction,
formatting, upper/capitalize, and general-case trimming until those surfaces
have bounded compiler contracts — upper/capitalize landed as the
`string-upper` LANGUAGE operation the same day. See
`migration/bounded-text-v1.edn`.

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
