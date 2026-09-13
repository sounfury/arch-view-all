(ns arch-view.input.languages
  (:require [clojure.java.io :as io]
            [arch-view.input.clojure.dependency-extract :as clojure-input]
            [arch-view.input.python.dependency-extract :as python-input]))

(def adapters
  {:clojure clojure-input/build-module-graph
   :python python-input/build-module-graph})

(defn language-key [language]
  (let [language (keyword (or language :clojure))]
    (when-not (contains? adapters language)
      (throw (ex-info (str "Unsupported language: " (name language)
                           ". Supported: clojure, python; Java is reserved.")
                      {:language language})))
    language))

(defn default-source-paths [project-path language]
  (if (and (= :python (language-key language))
           (not (.isDirectory (io/file project-path "src"))))
    ["."]
    ["src"]))

(defn build-module-graph [project-path source-paths language]
  ((get adapters (language-key language)) project-path source-paths))
