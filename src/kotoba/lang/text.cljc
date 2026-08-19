(ns kotoba.lang.text
  "String / regex / unicode helpers for the kotoba foundational stdlib. The gap
  every other lib re-rolls (json/lint/time hand-roll string ops). Pure string
  ops are portable; regex uses the host's #\"...\". `format` is a pure %s/%d
  impl (no String/format — WASM-safe). Runs on JVM/SCI/CLJS/GraalVM/kotoba-WASM.

  Zero third-party runtime deps; .cljc."
  (:refer-clojure :exclude [format split join replace re-find re-matches re-seq
                            reverse])
  (:require [clojure.string :as cstr]))

;; ---------- split / join ----------

(defn split
  "Split `s` on regex `re`, returning a vector of substrings."
  [s re] (cstr/split s re))

(defn split-lines
  "Split `s` into lines (handles \\n, \\r, \\r\\n)."
  [s] (cstr/split-lines s))

(defn join
  "Join `coll` with separator `sep`."
  [sep coll] (cstr/join sep coll))

;; ---------- trim ----------

(defn trim  [s] (cstr/trim s))
(defn triml [s] (cstr/triml s))
(defn trimr [s] (cstr/trimr s))

;; ---------- case ----------

(defn upper      [s] (cstr/upper-case s))
(defn lower      [s] (cstr/lower-case s))
(defn capitalize [s] (cstr/capitalize s))

;; ---------- predicates ----------

(defn starts-with? [s prefix] (cstr/starts-with? s prefix))
(defn ends-with?   [s suffix] (cstr/ends-with? s suffix))
(defn includes?    [s sub]    (cstr/includes? s sub))

;; ---------- regex (host) ----------

(defn replace
  "Replace all matches of `re` in `s` with `replacement` (string or fn)."
  [s re replacement] (cstr/replace s re replacement))

(defn replace-first
  "Replace the first match of `re` in `s` with `replacement`."
  [s re replacement] (cstr/replace-first s re replacement))

(defn re-find    [re s] (#?(:clj clojure.core/re-find :cljs cljs.core/re-find) re s))
(defn re-matches [re s] (#?(:clj clojure.core/re-matches :cljs cljs.core/re-matches) re s))
(defn re-seq
  "Return a lazy seq of matches of `re` in `s`. Returns nil if no match."
  [re s]
  (#?(:clj clojure.core/re-seq :cljs cljs.core/re-seq) re s))

;; ---------- format (pure %s/%d/%x) ----------

(defn- fmt-one [spec arg]
  (cond
    (= spec "s") (str arg)
    (= spec "d") (str (long arg))
    (= spec "x") (#?(:clj  (fn [n] (Long/toHexString n))
                     :cljs (fn [n] (.toString n 16))) (long arg))
    (= spec "f") (str (double arg))
    :else        (str "%" spec)))

(defn format
  "Pure printf-style formatting supporting %s %d %x %f and literal %%.
  `args` is a seq of values. No String/format — WASM-safe."
  [fmt args]
  (let [args (vec args)]
    (loop [i 0 ai 0 out (transient [])]
      (if (>= i (count fmt))
        (apply str (persistent! out))
        (let [c (nth fmt i)]
          (if (not= c \%)
            (recur (inc i) ai (conj! out c))
            ;; char after %
            (let [c2 (nth fmt (inc i) \space)]
              (if (= c2 \%)
                (recur (+ i 2) ai (conj! out \%))
                (recur (+ i 2) (inc ai) (conj! out (fmt-one (str c2) (args ai))))))))))))

;; ---------- unicode codepoints ----------

(defn codepoints
  "Return a vector of unicode codepoint ints for `s`. Portable (no
  String/codePoints which is JVM-only)."
  [s]
  (let [n (count s)]
    (loop [i 0 out (transient [])]
      (if (>= i n)
        (persistent! out)
        (let [c (nth s i)
              cp #?(:clj  (long c)
                    :cljs (.charCodeAt c 0))]
          ;; surrogate pair handling (basic): high surrogate + low surrogate
          (if (and (>= cp 0xD800) (<= cp 0xDBFF) (< (inc i) n))
            (let [c2 (nth s (inc i))
                  cp2 #?(:clj  (long c2)
                         :cljs (.charCodeAt c2 0))]
              (if (and (>= cp2 0xDC00) (<= cp2 0xDFFF))
                (let [combined (+ (* (- cp 0xD800) 0x400) (- cp2 0xDC00) 0x10000)]
                  (recur (+ i 2) (conj! out combined)))
                (recur (inc i) (conj! out cp))))
            (recur (inc i) (conj! out cp))))))))

(defn from-codepoints
  "Build a string from a seq of codepoint ints. Surrogates are emitted for
  codepoints > 0xFFFF (portable)."
  [cps]
  (let [f #?(:clj char :cljs js/String.fromCharCode)]
    (apply str
           (mapcat (fn [cp]
                     (if (> cp 0xFFFF)
                       ;; emit a surrogate pair
                       (let [cp' (- cp 0x10000)
                             hi (+ 0xD800 (quot cp' 0x400))
                             lo (+ 0xDC00 (mod cp' 0x400))]
                         [(f hi) (f lo)])
                       [(f cp)]))
                   cps))))

;; ---------- padding / truncate ----------

(defn pad-left  [s width ch] (let [pad (max 0 (- width (count s)))] (str (cstr/join (repeat pad ch)) s)))
(defn pad-right [s width ch] (let [pad (max 0 (- width (count s)))] (str s (cstr/join (repeat pad ch)))))

(defn truncate
  "Truncate `s` to `max-len` chars. If `ellipsis` is given and `s` is longer
  than `max-len`, the result is `max-len` chars including the ellipsis."
  ([s max-len] (subs s 0 (min (count s) max-len)))
  ([s max-len ellipsis]
   (if (<= (count s) max-len)
     s
     (str (subs s 0 (max 0 (- max-len (count ellipsis)))) ellipsis))))

;; ---------- remaining clojure.string parity ----------
;; blank? / index-of / last-index-of / reverse / trim-newline were the last
;; clojure.string primitives this oracle did not carry, forcing callers back
;; to `clojure.string` directly for them.

(defn blank?
  "True if `s` is nil, empty, or contains only whitespace."
  [s] (cstr/blank? s))

(defn index-of
  "Index of the first occurrence of `value` (string or char) in `s`, from
  `from-index` if given, or nil if not found."
  ([s value] (cstr/index-of s value))
  ([s value from-index] (cstr/index-of s value from-index)))

(defn last-index-of
  "Index of the last occurrence of `value` (string or char) in `s`, searching
  backward from `from-index` if given, or nil if not found."
  ([s value] (cstr/last-index-of s value))
  ([s value from-index] (cstr/last-index-of s value from-index)))

(defn reverse
  "Reverse the characters of `s`. Codepoint-naive (like clojure.string/reverse):
  use `codepoints`/`from-codepoints` for a surrogate-pair-safe reversal."
  [s] (cstr/reverse s))

(defn trim-newline
  "Remove trailing newline (\\n) or carriage-return+newline (\\r\\n) from `s`."
  [s] (cstr/trim-newline s))
