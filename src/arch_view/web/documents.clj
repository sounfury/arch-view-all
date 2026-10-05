;; 工具函数：查找已有的项目说明和包内说明，提取文件头注释，并限制文件读取范围。

(ns arch-view.web.documents
  "Optional existing documents and file headers. No AI or project writes."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.file Path]))

(defn root-path [root]
  (.toPath (.getCanonicalFile (io/file root))))

(defn contained-file [^Path root path]
  (let [file (.getCanonicalFile (io/file path))]
    (when-not (.startsWith (.toPath file) root)
      (throw (ex-info "文件必须位于当前项目内" {:status 403})))
    file))

(defn relative-path [^Path root file]
  (str/replace (str (.relativize root (.toPath (.getCanonicalFile (io/file file))))) "\\" "/"))

(defn- parent-directories [^Path root file]
  (loop [dir (.getParentFile (io/file file)) result []]
    (if (and dir (.startsWith (.toPath (.getCanonicalFile dir)) root))
      (recur (.getParentFile dir) (conj result dir))
      result)))

(defn- named-documents
  ([directory] (named-documents directory #{"readme.md" "architecture.md"}))
  ([directory names]
   (filter #(and (.isFile ^java.io.File %)
                 (contains? names (str/lower-case (.getName ^java.io.File %))))
           (.listFiles (io/file directory)))))

(defn discover [^Path root architecture explicit-path]
  (let [root-file (.toFile root)
        explicit (when explicit-path
                   (let [file (io/file explicit-path)
                         file (contained-file root (if (.isAbsolute file) file (io/file root-file explicit-path)))]
                     (when-not (and (.isFile file) (str/ends-with? (str/lower-case (.getName file)) ".md"))
                       (throw (ex-info "架构说明必须是项目内已存在的 Markdown 文件" {:status 400})))
                     file))
        source-files (filter #(.startsWith (.toPath (.getCanonicalFile (io/file %))) root)
                             (vals (get-in architecture [:graph :module->source-file])))
        dirs (distinct (mapcat #(parent-directories root %) source-files))
        candidates (concat (when explicit [explicit])
                           (filter #(.isFile ^java.io.File %)
                                   [(io/file root-file "ARCHITECTURE.md")
                                    (io/file root-file "docs/architecture.md")])
                           (named-documents root-file)
                           ;; 包目录只认 ARCHITECTURE.md；普通 README 没有子系统和数据流，切换过去只会丢失项目文档。
                           (mapcat #(named-documents % #{"architecture.md"}) dirs))]
    (->> candidates
         (map #(contained-file root %))
         distinct
         (mapv (fn [file]
                 (let [id (relative-path root file)
                       parent (.toPath (.getParentFile file))]
                   {:id id :path id
                    :title (or (second (re-find #"(?m)^#\s+(.+?)\s*#*\s*$" (slurp file :encoding "UTF-8")))
                               (.getName file))
                    :scope (if (or (= parent root) (= id "docs/architecture.md") (= file explicit))
                             :project :package)
                    :directory (relative-path root (.getParentFile file))}))))))

(defn read-document [root id]
  (let [file (contained-file root (io/file (.toFile ^Path root) id))]
    {:id id :path id :content (slurp file :encoding "UTF-8")}))

(defn- clean-comment [text]
  (->> (str/split-lines text)
       (map #(-> %
                 (str/replace #"^\s*(?:;+|#+|//+|\*+)\s?" "")
                 str/trimr))
       (remove #(or (re-find #"(?i)copyright|SPDX-License|coding[:=]|^!|^mutation-tested:" %)
                    (str/blank? %)))
       (str/join "\n")
       str/trim))

(defn header-description [source]
  (let [source (str/replace source #"^\uFEFF" "")
        lines (str/split-lines source)
        leading (->> lines
                     (drop-while #(or (str/blank? %) (str/starts-with? % "#!")))
                     (take-while #(or (str/blank? %) (re-find #"^\s*(?:[;#]|//)" %)))
                     (str/join "\n"))
        ;; Java/Kotlin 的类说明写在 package 和 import 之后，先跳过这些声明行。
        block (second (re-find #"(?s)^\s*(?:(?:package|import)\b[^\n]*\n\s*)*/\*+(.*?)\*/" source))
        python-doc (second (re-find #"(?s)^\s*(?:#![^\n]*\n\s*)?(?:#[^\n]*\n\s*)*[uUrR]?(?:\"\"\"|''')(.*?)(?:\"\"\"|''')" source))
        ns-doc (second (re-find #"(?s)\(ns\s+[^\s()]+\s+\"((?:\\.|[^\"\\])*)\"" source))
        text (if (seq (clean-comment leading)) leading (or block python-doc ns-doc ""))]
    (not-empty (clean-comment text))))
