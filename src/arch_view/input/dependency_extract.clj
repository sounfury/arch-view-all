;; 职责：读取源码中的依赖声明和明确的按需加载调用，找出模块依赖、抽象声明和对应文件。
;; 核心入口：构建模块关系图（build-module-graph）。

;; mutation-tested: 2026-03-08
(ns arch-view.input.dependency-extract
  (:require [clojure.java.io :as io]
            [arch-view.input.source-scan :as scan]
            [arch-view.model.graph :as graph]))

(declare ^:private namespace-in-file ^:private polymorphic-module?
         ^:private read-first-form ^:private dependency-symbols ^:private lazy-dependencies-in-file)

(def polymorphic-defs
  #{"defprotocol" "defmulti" "definterface"})

(defn build-module-graph
  [project-path source-paths]
  (let [files (scan/discover-source-files project-path source-paths)
        module-by-file (into {}
                             (for [f files
                                   :let [module (namespace-in-file f)]
                                   :when module]
                               [f module]))
        files-by-module (reduce (fn [acc [file module]]
                                  (update acc module (fnil conj []) file))
                                {}
                                module-by-file)
        module->source-file (into {}
                                  (for [[module module-files] files-by-module
                                        :let [best-file (->> module-files sort first)]]
                                    [module best-file]))
        nodes (set (vals module-by-file))
        abstract-modules (set
                          (for [[file module] module-by-file
                                :when (polymorphic-module? file)]
                            module))
        edges (set
               (mapcat (fn [[file from]]
                         (let [deps (into (dependency-symbols (read-first-form file))
                                          (lazy-dependencies-in-file file))]
                           (for [to deps
                                 :when (contains? nodes to)]
                             {:from from :to to})))
                       module-by-file))]
    (merge (graph/make-graph nodes edges)
           {:abstract-modules abstract-modules
            :module->source-file module->source-file})))

;; ===== 私有方法 =====

(defn- read-first-form
  [path]
  (with-open [r (java.io.PushbackReader. (io/reader path))]
    (binding [*read-eval* false]
      (read {:read-cond :allow :features #{:clj}} r))))

(defn- read-all-forms
  [path]
  (with-open [r (java.io.PushbackReader. (io/reader path))]
    (loop [forms []]
      (let [form (try
                   (binding [*read-eval* false]
                     (read {:read-cond :allow :features #{:clj}} r))
                   (catch java.io.EOFException _
                     ::eof)
                   (catch RuntimeException ex
                     (if (.startsWith (or (.getMessage ex) "") "EOF while reading")
                       ::eof
                       (throw ex))))]
        (if (= ::eof form)
          forms
          (recur (conj forms form)))))))

(defn- ns-form?
  [form]
  (and (seq? form)
       (= 'ns (first form))))

(defn- dependency-symbols
  [ns-form]
  (let [clauses (drop 2 ns-form)
        requires (->> clauses
                      (filter #(and (seq? %)
                                    (= :require (first %))))
                      (mapcat rest))]
    (->> requires
         (map (fn [req]
                (cond
                  (symbol? req) req
                  (vector? req) (first req)
                  :else nil)))
         (filter symbol?)
         (map str)
         set)))

(defn- namespace-in-file
  [path]
  (let [form (read-first-form path)]
    (when (ns-form? form)
      (str (second form)))))

(defn- polymorphic-module?
  [path]
  (try
    (some (fn [form]
            (and (seq? form)
                 (symbol? (first form))
                 (contains? polymorphic-defs (name (first form)))))
          (read-all-forms path))
    (catch RuntimeException _
      false)))

(defn- lazy-dependency-symbols
  [form]
  (cond
    ;; 引用数据和注释里的示例不代表代码真的依赖这些模块。
    (and (seq? form)
         (contains? #{'quote 'clojure.core/quote 'comment 'clojure.core/comment}
                    (first form))) #{}

    (and (seq? form)
         (contains? #{'requiring-resolve 'clojure.core/requiring-resolve} (first form)))
    (let [target (second form)
          quoted? (and (seq? target) (= 2 (count target))
                       (contains? #{'quote 'clojure.core/quote} (first target)))
          module (when (and (= 2 (count form)) quoted? (symbol? (second target)))
                   (namespace (second target)))]
      (if module #{module} #{}))

    (coll? form) (into #{} (mapcat lazy-dependency-symbols) form)
    :else #{}))

(defn- lazy-dependencies-in-file
  [path]
  (try
    (into #{} (mapcat lazy-dependency-symbols)
          (remove ns-form? (read-all-forms path)))
    ;; 非命名空间代码无法读取时，仍保留命名空间声明里的原有依赖。
    (catch RuntimeException _ #{})))
