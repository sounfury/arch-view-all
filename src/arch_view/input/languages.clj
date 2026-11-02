;; 职责：选择对应语言的分析器、确定默认扫描目录，并传递构建模块分组。
;; 核心入口：构建模块关系图（build-module-graph）。

(ns arch-view.input.languages
  (:require [clojure.java.io :as io]
            [arch-view.input.project-discovery :as discovery]
            [arch-view.input.clojure.dependency-extract :as clojure-input]
            [arch-view.input.python.dependency-extract :as python-input]))

(declare ^:private java-module-graph ^:private kotlin-module-graph)

(def adapters
  {:clojure clojure-input/build-module-graph
   :python python-input/build-module-graph
   :java (fn [project-path source-paths & [modules]]
           (java-module-graph project-path source-paths modules))
   :kotlin (fn [project-path source-paths & [modules]]
             (kotlin-module-graph project-path source-paths modules))})

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

(defn analysis-roots
  "仅在 Java/Kotlin 未覆盖源码路径且发现至少两个模块时启用模块分组。"
  [project-path source-paths language]
  (let [modules (when-not (seq source-paths) (discovery/build-modules project-path language))]
    (if (>= (count modules) 2)
      {:source-paths (vec (mapcat :source-paths modules)) :build-modules modules}
      {:source-paths (vec (or (seq source-paths) (default-source-paths project-path language)))})))

(defn build-module-graph
  ([project-path source-paths language]
   (build-module-graph project-path source-paths language {}))
  ([project-path source-paths language opts]
   (let [language (resolve-language project-path source-paths language)]
     (case language
       :python (python-input/build-module-graph project-path source-paths opts)
       (:java :kotlin) ((get adapters language) project-path source-paths (:build-modules opts))
       ((get adapters language) project-path source-paths)))))

;; ===== 私有方法 =====

(defn- kotlin-module-graph [project-path source-paths modules]
  ((requiring-resolve 'arch-view.input.kotlin.dependency-extract/build-module-graph)
   project-path source-paths modules))

(defn- java-module-graph [project-path source-paths modules]
  ;; 只在选择 Java 时检查并加载编译器，其他语言不需要加载它。
  (try
    (Class/forName "com.sun.source.util.JavacTask")
    (catch ClassNotFoundException ex
      (throw (ex-info "Java 分析需要包含编译器模块（jdk.compiler）的完整开发环境（JDK）。"
                      {:language :java} ex))))
  ((requiring-resolve 'arch-view.input.java.dependency-extract/build-module-graph)
   project-path source-paths modules))
