(ns kotoba.lang.text-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.lang.text :as t]))

(deftest split-join
  (is (= ["a" "b" "c"] (t/split "a,b,c" #",")))
  (is (= ["a" "b"] (t/split-lines "a\nb")))
  (is (= "a-b-c" (t/join "-" ["a" "b" "c"]))))

(deftest trim-and-case
  (is (= "hi" (t/trim "  hi  ")))
  (is (= "hi" (t/triml "  hi")))
  (is (= "hi" (t/trimr "hi  ")))
  (is (= "HI" (t/upper "hi")))
  (is (= "hi" (t/lower "HI")))
  (is (= "Hello" (t/capitalize "hello"))))

(deftest predicates
  (is (true?  (t/starts-with? "abc" "ab")))
  (is (true?  (t/ends-with? "abc" "bc")))
  (is (true?  (t/includes? "abc" "b")))
  (is (false? (t/starts-with? "abc" "x"))))

(deftest regex
  (is (= "XXX" (t/replace "abc" #"a|b|c" "X")))
  (is (= "Xbc" (t/replace-first "abc" #"a" "X")))
  (is (some? (t/re-find #"\d+" "abc123")))
  (is (some? (t/re-seq #"\d" "a1b2")))
  (is (nil? (t/re-seq #"\d" "abc"))))

(deftest format
  (is (= "x = 42" (t/format "%s = %d" ["x" 42])))
  (is (= "100%" (t/format "100%%" [])))
  (is (= "ff" (t/format "%x" [255])))
  (is (= "1.5" (t/format "%f" [1.5]))))

(deftest codepoints
  (is (= [97 98] (t/codepoints "ab")))
  (is (= "ab" (t/from-codepoints [97 98])))
  ;; astral plane roundtrip (emoji U+1F600)
  (is (= "😀" (t/from-codepoints [0x1F600])))
  (is (= [0x1F600] (t/codepoints "😀"))))

(deftest pad-and-truncate
  (is (= "007" (t/pad-left "7" 3 \0)))
  (is (= "7  " (t/pad-right "7" 3 \space)))
  (is (= "hel" (t/truncate "hello" 3)))
  (is (= "h..." (t/truncate "hello" 4 "...")))
  (is (= "hello" (t/truncate "hello" 10 "..."))))
