(ns arch-view.input.languages
  (:require [clojure.java.io :as io]
            [arch-view.input.project-discovery :as discovery]
            [arch-view.input.clojure.dependency-extract :as clojure-input]
            [arch-view.input.python.dependency-extract :as python-input]))

(defn- java-module-graph [project-path source-paths]
  ;; Java compiler classes are optional for users of the other language adapters.
  (try
    (Class/forName "com.sun.source.util.JavacTask")
    (catch ClassNotFoundException ex
      (throw (ex-info "Java analysis requires a full JDK with the jdk.compiler module."
                      {:language :java} ex))))
  ((requiring-resolve 'arch-view.input.java.dependency-extract/build-module-graph)
   project-path source-paths))

(def adapters
  {:clojure clojure-input/build-module-graph
   :python python-input/build-module-graph
   :java java-module-graph})

(defn language-key [language]
  (let [language (keyword (or language :auto))]
    (when-not (or (= :auto language) (contains? adapters language))
      (throw (ex-info (str "Unsupported language: " (name language)
                           ". Supported: auto, clojure, python, java.")
                      {:language language})))
    language))

(defn resolve-language [project-path source-paths language]
  (let [language (language-key language)]
    (if (= :auto language)
      (discovery/detect-language project-path source-paths)
      language)))

(defn default-source-paths [project-path language]
  (case (resolve-language project-path nil language)
    :java (discovery/java-source-paths project-path)
    :python (if (.isDirectory (io/file project-path "src")) ["src"] ["."])
    ["src"]))

(defn build-module-graph
  ([project-path source-paths language]
   (build-module-graph project-path source-paths language {}))
  ([project-path source-paths language opts]
   (let [language (resolve-language project-path source-paths language)]
     (if (= :python language)
       (python-input/build-module-graph project-path source-paths opts)
       ((get adapters language) project-path source-paths)))))
