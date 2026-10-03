;; 职责：选择对应语言的分析器，并确定默认扫描目录，统一不同语言的分析入口。
;; 核心入口：构建模块关系图（build-module-graph）。

(ns arch-view.input.languages
  (:require [clojure.java.io :as io]
            [arch-view.input.project-discovery :as discovery]
            [arch-view.input.clojure.dependency-extract :as clojure-input]
            [arch-view.input.python.dependency-extract :as python-input]))

(declare ^:private java-module-graph)

(def adapters
  {:clojure clojure-input/build-module-graph
   :python python-input/build-module-graph
   :java (fn [project-path source-paths]
           (java-module-graph project-path source-paths))
   :kotlin (fn [project-path source-paths]
             ((requiring-resolve 'arch-view.input.kotlin.dependency-extract/build-module-graph)
              project-path source-paths))})

(defn language-key [language]
  (let [language (keyword (or language :auto))]
    (when-not (or (= :auto language) (contains? adapters language))
      (throw (ex-info (str "Unsupported language: " (name language)
                           ". Supported: auto, clojure, python, kotlin, java.")
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
    (:python :kotlin) (if (.isDirectory (io/file project-path "src")) ["src"] ["."])
    ["src"]))

(defn build-module-graph
  ([project-path source-paths language]
   (build-module-graph project-path source-paths language {}))
  ([project-path source-paths language opts]
   (let [language (resolve-language project-path source-paths language)]
     (if (= :python language)
       (python-input/build-module-graph project-path source-paths opts)
       ((get adapters language) project-path source-paths)))))

;; ===== 私有方法 =====

(defn- java-module-graph [project-path source-paths]
  ;; 只在选择 Java 时检查并加载编译器，其他语言不需要加载它。
  (try
    (Class/forName "com.sun.source.util.JavacTask")
    (catch ClassNotFoundException ex
      (throw (ex-info "Java 分析需要包含编译器模块（jdk.compiler）的完整开发环境（JDK）。"
                      {:language :java} ex))))
  ((requiring-resolve 'arch-view.input.java.dependency-extract/build-module-graph)
   project-path source-paths))
