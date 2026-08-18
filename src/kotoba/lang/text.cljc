(ns kotoba.lang.text
  "String / regex / unicode helpers for the kotoba foundational stdlib. The gap
  every other lib re-rolls (json/lint/time hand-roll string ops). Pure string
  ops are portable; regex uses the host's #\"...\". `format` is a pure printf
  impl with flags/width/precision (no String/format — WASM-safe), varargs like
  clojure.core/format, which is JVM-only. Runs on JVM/SCI/CLJS/GraalVM/kotoba-WASM.

  Zero third-party runtime deps; .cljc."
  (:refer-clojure :exclude [format split join replace re-find re-matches re-seq])
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
