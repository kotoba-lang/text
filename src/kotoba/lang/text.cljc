(ns kotoba.lang.text
  "String / regex / unicode helpers for the kotoba foundational stdlib. The gap
  every other lib re-rolls (json/lint/time hand-roll string ops). Pure string
  ops are portable; regex uses the host's #\"...\". `format` is a pure printf
  impl with flags/width/precision (no String/format — WASM-safe), varargs like
  clojure.core/format, which is JVM-only. Runs on JVM/SCI/CLJS/GraalVM/kotoba-WASM.

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

;; ---------- format (pure printf: flags, width, precision) ----------
;;
;; Replaces clojure.core/format, which is JVM-only -- it wraps String.format,
;; so nbb resolves it to nil and every portable namespace that calls it is
;; pinned to the JVM. Measured 2026-08-18 across this workspace: 673 production
;; .clj files call `format`, second only to `spit`.
;;
;; The earlier implementation here handled bare %s %d %x %f and fell through to
;; a literal for anything else. Measured over the 5,968 specifiers actually in
;; use, 944 of them (16%) carry a width or precision -- %.1f (176), %02x (133),
;; %.2f (119), %-10s (32), %064x (17). Those did not error; they emitted "%."
;; followed by the rest as literal text. Silently wrong output is worse than a
;; missing function, which is why this grammar exists.
;;
;; %064x in particular is how a 32-byte hash is rendered. Getting it wrong is
;; not cosmetic.

(defn- pad
  "Pad `s` to `width` with `fill`, on the left unless `left?`."
  [s width left? fill]
  (let [n (- width (count s))]
    (if (pos? n)
      (let [p (apply str (repeat n fill))]
        (if left? (str s p) (str p s)))
      s)))

(defn- fixed
  "Round `x` to `prec` decimal places without String/format or goog. Uses
  integer arithmetic on the scaled value so the result does not depend on the
  host's float printing."
  [x prec]
  (let [neg? (neg? x)
        x (Math/abs (double x))
        scale (Math/pow 10 prec)
        scaled (Math/round (* x scale))
        i (long (quot scaled scale))
        f (long (- scaled (* i scale)))
        frac (when (pos? prec) (pad (str f) prec false \0))]
    (str (when neg? "-") i (when frac (str "." frac)))))

(defn- to-hex [n]
  #?(:clj (Long/toHexString (long n))
     :cljs (.toString (long n) 16)))

(defn- fmt-one [{:keys [flags width prec conv]} arg]
  (let [left? (cstr/includes? flags "-")
        zero? (and (cstr/includes? flags "0") (not left?))
        body (case conv
               ;; clojure.core/format renders nil as "null" (it defers to
               ;; String.valueOf), not as the empty string that `str` gives.
               ;; Caught by the differential test, not by reading the code.
               "s" (let [v (if (nil? arg) "null" (str arg))]
                     (if prec (subs v 0 (min prec (count v))) v))
               "d" (str (long arg))
               "x" (to-hex arg)
               "X" (upper (to-hex arg))
               "o" #?(:clj (Long/toOctalString (long arg)) :cljs (.toString (long arg) 8))
               "f" (fixed arg (or prec 6))
               "c" (str (char (if (number? arg) (long arg) arg)))
               "b" (str (boolean arg))
               (str "%" conv))
        body (if (and (cstr/includes? flags "+") (#{"d" "f"} conv) (not (cstr/starts-with? body "-")))
               (str "+" body) body)]
    (if width (pad body width left? (if zero? \0 \space)) body)))

(def ^:private spec-re #"%([-+ 0#]*)(\d+)?(?:\.(\d+))?([a-zA-Z%])")

(defn format
  "printf-style formatting with flags, width and precision -- %s %d %x %X %o
  %f %c %b and literal %%. VARARGS, like clojure.core/format, which this
  replaces; clojure.core/format is JVM-only.

  A seq may still be passed as a single second argument, which is how this
  function used to be called; that form is kept so existing callers do not
  change meaning. The two are distinguishable because the seq form takes
  exactly one extra argument and it is sequential."
  [fmt & args]
  (let [args (vec (if (and (= 1 (count args)) (sequential? (first args)))
                    (first args)
                    args))]
    (loop [i 0 ai 0 out (transient [])]
      (if (>= i (count fmt))
        (apply str (persistent! out))
        (let [c (nth fmt i)]
          (if (not= c \%)
            (recur (inc i) ai (conj! out c))
            (if-let [m (re-find spec-re (subs fmt i (min (count fmt) (+ i 16))))]
              (let [[whole flags w p conv] m]
                (if (= conv "%")
                  (recur (+ i (count whole)) ai (conj! out \%))
                  (recur (+ i (count whole)) (inc ai)
                         (conj! out (fmt-one {:flags (or flags "")
                                              :width (when w #?(:clj (Long/parseLong w) :cljs (js/parseInt w)))
                                              :prec  (when p #?(:clj (Long/parseLong p) :cljs (js/parseInt p)))
                                              :conv  conv}
                                             (nth args ai nil))))))
              (recur (inc i) ai (conj! out c)))))))))

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

;; ---------- bounded-kernel oracle (2026-09-04) ----------
;;
;; The `.kotoba` kernel (`bounded_text.kotoba`) now owns a trim/reverse/
;; repeat/index/pad tranche composed from the language's string builtins.
;; These CLJC functions are their ORACLE: same names modulo the `-text`
;; suffix, same semantics INCLUDING the differences the bounded kernel
;; names rather than hides -- ASCII-only whitespace (kernel `ws?`),
;; UTF-8 byte offsets instead of UTF-16 code units (kernel index answers),
;; and a fill string instead of a fill char (kernel pad). A caller migrating
;; from `clojure.string` should meet the same divergence in both.

(defn- ascii-ws?
  "The kernel's whitespace class: ASCII space, tab, newline, CR, FF, VT.
  Unicode spaces (U+00A0, U+3000, ...) are NOT whitespace here, where
  clojure.string/trim's Character/isWhitespace answers for them."
  [^long cp]
  (contains? #{32 9 10 13 12 11} cp))

(defn- codepoints-of
  "Code points of s as a vector (text/codepoints, reused here so the oracle
  and kernel walk the same sequence)."
  [s]
  (codepoints s))

(defn trim-text
  "Oracle for the kernel's trim-text: strips ASCII whitespace from both ends."
  [s]
  (let [cps (vec (codepoints-of s))
        n (count cps)
        lead (loop [i 0] (if (and (< i n) (ascii-ws? (nth cps i))) (recur (inc i)) i))
        trail (loop [i (dec n)] (if (and (>= i 0) (ascii-ws? (nth cps i))) (recur (dec i)) i))]
    (if (> lead trail) "" (from-codepoints (subvec cps lead (inc trail))))))

(defn triml-text
  "Oracle for the kernel's triml-text: strips ASCII whitespace from the left."
  [s]
  (let [cps (vec (codepoints-of s))
        n (count cps)
        lead (loop [i 0] (if (and (< i n) (ascii-ws? (nth cps i))) (recur (inc i)) i))]
    (from-codepoints (subvec cps lead))))

(defn trimr-text
  "Oracle for the kernel's trimr-text: strips ASCII whitespace from the right."
  [s]
  (let [cps (vec (codepoints-of s))
        n (count cps)
        trail (loop [i (dec n)] (if (and (>= i 0) (ascii-ws? (nth cps i))) (recur (dec i)) i))]
    (if (neg? trail) "" (from-codepoints (subvec cps 0 (inc trail))))))

(defn blank-text?
  "Oracle for the kernel's blank-text?: empty or ASCII whitespace only."
  [s]
  (= "" (trim-text s)))

(defn reverse-text
  "Oracle for the kernel's reverse-text: code-point-safe reversal (the kernel
  walks UTF-8 code points; this walks code points too, so astral characters
  survive -- unlike clojure.string/reverse on a surrogate pair). rseq, not
  cstr/reverse: this namespace's own `reverse` wraps cstr/reverse, which is
  string-shaped and would see a vector."
  [s]
  (from-codepoints (vec (rseq (vec (codepoints-of s))))))

(defn repeat-text
  "Oracle for the kernel's repeat-text: s repeated times times; zero or
  negative times answer the empty string (clojure.core/repeat is lazy and
  has no negative case)."
  [s times]
  (if (pos? times) (apply str (repeat times s)) ""))

(defn- utf8-byte-offsets
  "Byte offset of each code point index in cps (one past the end for the
  count). The kernel's index answers are UTF-8 byte offsets; this is how the
  oracle converts its code point indexes to the same units."
  [cps]
  (let [width (fn [cp] (cond (< cp 0x80) 1 (< cp 0x800) 2 (< cp 0x10000) 3 :else 4))]
    (loop [i 0 acc 0 offsets [0]]
      (if (= i (count cps))
        offsets
        (recur (inc i) (+ acc (width (nth cps i))) (conj offsets (+ acc (width (nth cps i)))))))))

(defn index-of-text
  "Oracle for the kernel's index-of-text: UTF-8 BYTE offset of the first
  occurrence, or -1. clojure.string/index-of answers a UTF-16 code unit
  index or nil; they agree only on ASCII."
  [s value]
  (let [cps (vec (codepoints-of s))
        needle (vec (codepoints-of value))
        n (count cps)
        m (count needle)
        offsets (utf8-byte-offsets cps)]
    (loop [i 0]
      (cond
        (> (+ i m) n) -1
        (= m 0) (nth offsets i)
        (= (subvec cps i (+ i m)) needle) (nth offsets i)
        :else (recur (inc i))))))

(defn last-index-of-text
  "Oracle for the kernel's last-index-of-text: UTF-8 BYTE offset of the last
  occurrence, or -1 (clojure.string/last-index-of answers a UTF-16 index)."
  [s value]
  (let [cps (vec (codepoints-of s))
        needle (vec (codepoints-of value))
        n (count cps)
        m (count needle)
        offsets (utf8-byte-offsets cps)]
    (loop [i 0 best -1]
      (if (> i (- n m))
        best
        (recur (inc i)
               (if (= (subvec cps i (+ i m)) needle)
                 (nth offsets i)
                 best))))))

(defn pad-left-text
  "Oracle for the kernel's pad-left-text: prepend fill until the string's
  BYTE length reaches width. The kernel measures bytes; this measures code
  points, which agree on ASCII fills -- the divergence the kernel names."
  [s width fill]
  (let [w (count (codepoints-of s))]
    (if (>= w width)
      s
      (if (empty? fill)
        s
        (loop [acc s]
          (if (>= (count (codepoints-of acc)) width)
            acc
            (recur (str fill acc))))))))

(defn pad-right-text
  "Oracle for the kernel's pad-right-text: append fill until width."
  [s width fill]
  (let [w (count (codepoints-of s))]
    (if (>= w width)
      s
      (if (empty? fill)
        s
        (loop [acc s]
          (if (>= (count (codepoints-of acc)) width)
            acc
            (recur (str acc fill))))))))
