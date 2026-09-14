(ns arch-view.input.python.dependency-extract
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [arch-view.model.graph :as graph]))

(defn- run-analyzer [executable script project-path source-paths include-tests]
  (let [error-file (java.io.File/createTempFile "arch-view-python-" ".log")]
    (try
      (let [process (-> (ProcessBuilder. ^java.util.List
                                         (vec (concat [executable "-X" "utf8" "-" project-path]
                                                      (when include-tests ["--include-tests"]) source-paths)))
                        (.redirectError error-file)
                        (.start))]
        (try
          (with-open [input (io/writer (.getOutputStream process) :encoding "UTF-8")]
            (.write input script))
          (let [out (with-open [output (.getInputStream process)]
                      (slurp output :encoding "UTF-8"))]
            {:exit (.waitFor process) :out out :err (slurp error-file :encoding "UTF-8")})
          (finally (.destroy process))))
      (finally (.delete error-file)))))

(defn build-module-graph
  ([project-path source-paths] (build-module-graph project-path source-paths {}))
  ([project-path source-paths {:keys [python include-tests]}]
  (let [executable (or python (System/getenv "ARCH_VIEW_PYTHON") "python")
        script (slurp (io/resource "arch_view/input/python/analyzer.py") :encoding "UTF-8")
        {:keys [exit out err]}
        (try
          (run-analyzer executable script project-path source-paths include-tests)
          (catch java.io.IOException ex
            (throw (ex-info "Python 3 is required; set ARCH_VIEW_PYTHON to its executable path."
                            {:executable executable} ex))))]
    (when-not (zero? exit)
      (throw (ex-info (str "Python dependency analysis failed: " err) {:exit exit})))
    (let [result (edn/read-string out)]
      (merge (graph/make-graph (get result "nodes")
                              (map (fn [[from to]] {:from from :to to})
                                   (get result "edges")))
             {:abstract-modules (set (get result "abstract-modules"))
              :module->source-file (get result "module->source-file")})))))
