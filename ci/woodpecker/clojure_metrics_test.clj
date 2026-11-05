(ns clojure-metrics-test
  (:require [clojure.test :refer [deftest is run-tests testing]]
            [clojure-metrics :as metrics]))

(defn values [source metric]
  (mapv :value (metrics/analyze-source source metric)))

(deftest decision-depth-and-cognitive-weight
  (let [source "(defn f [x] (let [y x] (if y (when (and x y) (if x y 0)) 0)))"]
    (is (= [3] (values source "nesting")))
    (is (= [7] (values source "cognitive"))))
  (is (= [2] (values "(defn f [x] (if x 1 0) (if x 2 3))" "cognitive"))))

(deftest ignores-data-strings-comments-and-discarded-forms
  (is (= [0] (values "(defn f [] \"(if x (if y z))\" ; (if x y)\n '(if x y) #_(if x y) (comment (if x y)))"
                      "cognitive")))
  (is (empty? (metrics/analyze-source "'(defn f [] (if x y))" "cognitive"))))

(deftest multi-arity-and-destructuring-are-arguments-not-binding-leaves
  (is (= [3] (values "(defn ^:private f \"doc\" ([{:keys [a b]}] a) ([x [a b] & rest] x))"
                      "params"))))

(deftest nested-functions-are-independent
  (let [source "(defn outer [x] (if x (fn [y] (if y (when y x) 0)) nil))"]
    (is (= [1 3] (values source "cognitive")))
    (is (= [1 2] (values source "nesting")))))

(deftest dispatch-values-and-shorthand-functions
  (is (= [2] (values "(defmethod f [:dispatch :value] [x y] x)" "params")))
  (is (= [3] (values "#(vector % %2 %&)" "params")))
  (is (= [1] (values "#(if % %2 0)" "cognitive"))))

(deftest gate-boundaries-and-empty-input
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "quality-fixture-" (make-array java.nio.file.attribute.FileAttribute 0)))
        source (clojure.java.io/file root "sample.clj")]
    (try
      (spit source "(defn f [x y z] x)")
      (binding [*out* (java.io.StringWriter.)]
        (is (= 0 (metrics/check! "params" 3 (str root) ["sample.clj"])))
        (is (= 2 (metrics/check! "params" 2 (str root) ["sample.clj"]))))
      (spit source "(ns empty-project)")
      (is (thrown? Exception (metrics/check! "params" 3 (str root) ["sample.clj"])))
      (finally (.delete source) (.delete root)))))

(deftest syntax-errors-do-not-pass
  (is (thrown? Exception (doall (metrics/analyze-source "(defn broken [x]" "cognitive")))))

(deftest physical-file-length
  (is (= [3] (values "; comment\n\n(def x 1)\n" "file-length"))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'clojure-metrics-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
