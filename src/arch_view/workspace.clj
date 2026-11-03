;; 职责：为查询、上下文包、链路和解释功能提供统一的工作区：项目根、数据目录、文件与模块互查、目标解析和当前提交。
;; 核心入口：目标解析（resolve-target）；文件对应模块（module-for-path）。

(ns arch-view.workspace
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import [java.nio.file Path]))

(declare ^:private source-index ^:private canonical-path ^:private package-members ^:private candidates)

(defn create
  "由分析结果和项目目录构造工作区；网页服务的 state 也满足同样的键。"
  [architecture project-path opts]
  {:architecture architecture
   :root (.toPath (.getCanonicalFile (io/file project-path)))
   :opts opts})

(defn data-dir [{:keys [root]}]
  (io/file (.toFile ^Path root) ".arch-view"))

(defn relative
  "相对项目根、正斜杠分隔的路径；相对路径按项目根解析。"
  [{:keys [root]} path]
  (let [file (io/file path)
        file (if (.isAbsolute file) file (io/file (.toFile ^Path root) (str path)))]
    (str/replace (str (.relativize ^Path root (.toPath (.getCanonicalFile file)))) "\\" "/")))

(defn source-file
  "模块源码文件的相对路径；没有源码文件时返回 nil。"
  [ws module]
  (get (:module->path (source-index ws)) module))

(defn module-for-path [ws path]
  (when (and path (not (str/blank? (str path))))
    (get (:path->module (source-index ws)) (relative ws path))))

(defn resolve-target
  "依次按完整模块名、源码文件、包前缀解析目标；都不匹配时抛出 404 并附候选模块。"
  [ws target]
  (let [target (str/trim (str target))
        nodes (get-in ws [:architecture :graph :nodes])]
    (when (str/blank? target) (throw (ex-info "缺少目标：请传入模块名、源码文件或包名" {:status 400})))
    (or (when (contains? nodes target) {:kind :module :target target :modules [target]})
        (when-let [module (try (module-for-path ws target) (catch Exception _ nil))]
          {:kind :file :target module :path (relative ws target) :modules [module]})
        (when-let [members (seq (package-members nodes (str/replace target #"\.+$" "")))]
          {:kind :package :target (str/replace target #"\.+$" "") :modules (vec members)})
        (throw (ex-info (str "未找到目标：" target) {:status 404 :candidates (candidates nodes target)})))))

(defn git-head [{:keys [root]}]
  (try
    (let [{:keys [exit out]} (shell/sh "git" "rev-parse" "HEAD" :dir (.toFile ^Path root))]
      (when (zero? exit) (str/trim out)))
    (catch Exception _ nil)))

;; ===== 私有方法 =====

(defn- canonical-path [ws file]
  (try (relative ws file) (catch Exception _ nil)))

(def ^:private index-cache (atom {}))

(defn- source-index
  "按分析结果缓存“模块 ↔ 相对路径”两张表；每份分析结果只算一次。"
  [{:keys [architecture root] :as ws}]
  (let [files (get-in architecture [:graph :module->source-file])
        cache-key [(System/identityHashCode files) (str root)]]
    (or (get @index-cache cache-key)
        (let [module->path (into {} (for [[module file] files
                                          :let [path (when file (canonical-path ws file))]
                                          :when path]
                                      [module path]))
              index {:module->path module->path
                     :path->module (into {} (map (fn [[m p]] [p m])) module->path)}]
          (swap! index-cache (fn [cache] (assoc (if (> (count cache) 16) {} cache) cache-key index)))
          index))))

(defn- package-members [nodes prefix]
  (when (seq prefix)
    (let [dotted (str prefix ".")]
      (sort (filter #(str/starts-with? % dotted) nodes)))))

(defn- candidates [nodes target]
  (let [needle (str/lower-case target)]
    (->> nodes (filter #(str/includes? (str/lower-case %) needle)) sort (take 10) vec)))
