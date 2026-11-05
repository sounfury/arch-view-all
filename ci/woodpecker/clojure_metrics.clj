;; Standalone syntax metrics. Parses source without loading the target project.
(ns clojure-metrics
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [rewrite-clj.node :as node]
            [rewrite-clj.parser :as parser]))

(def ignored-tags #{:whitespace :newline :comment :comma})
(def quoted-tags #{:quote :syntax-quote :uneval})
(def decisions
  #{"if" "if-not" "if-let" "if-some" "when" "when-not" "when-let"
    "when-some" "when-first" "cond" "condp" "case" "cond->" "cond->>"
    "some->" "some->>" "loop" "loop*" "for" "doseq" "while" "catch"})
(def named-functions #{"defn" "defn-" "defmacro" "defmethod"})

(defn children [n]
  (if (node/inner? n)
    (remove #(ignored-tags (node/tag %)) (node/children n))
    []))

(defn token [n]
  (when n
    (if (= :meta (node/tag n))
      (token (last (children n)))
      (node/string n))))

(defn head [n]
  (when (= :list (node/tag n))
    (some-> (first (children n)) token (str/split #"/") last)))

(defn executable-nodes [n]
  (when-not (or (quoted-tags (node/tag n)) (= "comment" (head n)))
    (cons n (mapcat executable-nodes (children n)))))

(defn function? [n]
  (or (named-functions (head n)) (#{"fn" "fn*"} (head n))
      (= :fn (node/tag n))))

(defn arguments [n]
  (let [parts (children n)
        tail (cond (= "defmethod" (head n)) (drop 3 parts)
                   (named-functions (head n)) (drop 2 parts)
                   :else (rest parts))
        direct (first (filter #(= :vector (node/tag %)) tail))]
    (if direct
      [direct]
      (keep #(when (= :vector (some-> % children first node/tag))
               (first (children %))) tail))))

(defn parameter-count [n]
  (if (= :fn (node/tag n))
    (let [args (keep #(when (= :token (node/tag %))
                        (re-matches #"%(\d+|&)?" (token %)))
                     (executable-nodes n))
          fixed (reduce max 0 (for [[_ arg] args :when (not= arg "&")]
                                (if arg (Long/parseLong arg) 1)))]
      (+ fixed (if (some #(= "&" (second %)) args) 1 0)))
    (reduce max 0
            (map #(count (remove (fn [arg] (= "&" (token arg)))
                                 (children %))) (arguments n)))))

(defn function-name [n]
  (if (named-functions (head n))
    (token (second (children n)))
    (str "<fn@" (:row (meta n)) ">")))

(defn score
  "Cognitive: structural decisions cost 1 + decision depth, and/or cost 1.
  Nesting: maximum decision depth. Function, let and do wrappers cost zero.
  Nested functions are measured separately, quotes/comments are ignored."
  [n depth metric]
  (let [op (head n)]
    (cond
      (or (quoted-tags (node/tag n)) (= "comment" op)) 0
      (function? n) 0
      :else
      (let [decision? (contains? decisions op)
            next-depth (if decision? (inc depth) depth)
            own (if (= metric "cognitive")
                  (cond decision? (inc depth) (#{"and" "or"} op) 1 :else 0)
                  next-depth)
            nested (map #(score % next-depth metric) (children n))]
        (if (= metric "cognitive")
          (+ own (reduce + 0 nested))
          (reduce max own nested))))))

(defn function-score [n metric]
  (cond
    (= metric "params") (parameter-count n)
    (= :fn (node/tag n)) (score (node/list-node (node/children n)) 0 metric)
    :else
    (let [values (map #(score % 0 metric) (children n))]
      (reduce (if (= metric "cognitive") + max) 0 values))))

(defn analyze-source [source metric]
  (if (= metric "file-length")
    [{:line 1 :name "<file>" :value (count (str/split-lines source))}]
    (for [n (executable-nodes (parser/parse-string-all source))
          :when (function? n)]
      {:line (or (:row (meta n)) 1)
       :name (function-name n)
       :value (function-score n metric)})))

(defn source-files [root paths]
  (->> paths
       (map #(io/file root %))
       (mapcat (fn [path]
                 (when-not (.exists path)
                   (throw (ex-info "Source path does not exist" {:path (str path)})))
                 (if (.isDirectory path) (file-seq path) [path])))
       (filter #(.isFile %))
       (filter #(re-find #"\.(clj|cljc|cljs|bb)$" (.getName %)))
       distinct
       (sort-by str)))

(defn check! [metric limit root paths]
  (when-not (#{"cognitive" "nesting" "params" "file-length"} metric)
    (throw (ex-info "Unknown metric" {:metric metric})))
  (let [files (source-files root paths)
        results (doall
                 (for [file files
                       result (analyze-source (slurp file) metric)]
                   (assoc result :file (.relativize (.toPath (io/file root))
                                                   (.toPath file)))))
        violations (filter #(> (:value %) limit) results)]
    (when (or (empty? files) (empty? results))
      (throw (ex-info "No source or measurable functions found" {:metric metric})))
    (doseq [{:keys [file line name value]} violations]
      (println (str file ":" line ": " name " " metric "=" value " > " limit)))
    (println (format "%s: %d files, %d measurements, %d violations (max %d)"
                     metric (count files) (count results) (count violations) limit))
    (if (seq violations) 2 0)))

(defn -main [metric limit root & paths]
  (try
    (System/exit (check! metric (Long/parseLong limit) root paths))
    (catch Exception error
      (binding [*out* *err*]
        (println "Quality analysis failed:" (.getMessage error) (ex-data error)))
      (System/exit 1))))
