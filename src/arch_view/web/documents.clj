;; 工具函数：查找已有的项目说明和包内说明，提取文件头注释（合并版权头与类说明，Javadoc 转 Markdown），并限制文件读取范围。

(ns arch-view.web.documents
  "Optional existing documents and file headers. No AI or project writes."
  (:require [arch-view.web.javadoc :as javadoc]
            [clojure.java.io :as io]
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

;; 版权头里的版权行和公司署名都是样板；署名只在同一段出现版权行时才去掉，避免误伤正文。
(def ^:private license-line #"(?i)copyright|all rights reserved|SPDX-License|licensed under")
(def ^:private signature-line
  #"(?i)^[\p{L}\p{N}.,&()（） \-]*?(?:\binc|\bltd|\bcorp|\bllc|\bgmbh|\.com|有限公司|集团)\.?$")
(def ^:private noise-line #"(?i)coding[:=]|^!|^mutation-tested:")

(defn- strip-license [lines]
  (if (some #(re-find license-line %) lines)
    (remove #(or (re-find license-line %) (re-find signature-line (str/trim %))) lines)
    lines))

(defn- clean-lines [lines]
  (->> lines
       (map #(str/trim (str/replace % #"^\s*(?:;+|#+|//+|\*+)\s?" "")))
       strip-license
       (remove #(or (re-find noise-line %) (str/blank? %)))
       (str/join "\n")))

(defn- clean-comment [text]
  (clean-lines (str/split-lines text)))

;; Java/Kotlin 前导区：空白、注释、package/import 与文件级注解，到第一行代码为止。
(def ^:private prelude-token
  #"\A(?:\s+|//[^\n]*|(?s:/\*.*?\*/)|(?:package|import)\b[^\n]*|@file:[^\n]*|#![^\n]*)")

(defn- prelude-segments
  "返回前导区里的注释段；连续的 // 行合成一段，空行、声明或块注释会断开。"
  [source]
  (loop [text source segments [] open? false]
    (if-let [token (re-find prelude-token text)]
      (let [text (subs text (count token))
            same-run? (and open? (str/blank? token) (< (count (re-seq #"\n" token)) 2))]
        (cond
          (and (str/starts-with? token "//") open?) (recur text (conj (pop segments) (str (peek segments) "\n" token)) true)
          (str/starts-with? token "//") (recur text (conj segments token) true)
          (str/starts-with? token "/*") (recur text (conj segments token) false)
          :else (recur text segments same-run?)))
      segments)))

(defn- segment-text [segment]
  (if (str/starts-with? segment "//")
    (not-empty (clean-comment segment))
    (let [body (str/replace segment #"(?s)\A/\*+(.*?)\*+/\z" "$1")]
      (javadoc/markdown (strip-license (map #(str/replace % #"^\s*\*+ ?" "") (str/split-lines body)))))))

(defn- script-description
  "Clojure/Python 等脚本式源码：开头的 ; 或 # 行注释优先，其次是模块文档字符串。"
  [source]
  (let [leading (->> (str/split-lines source)
                     (drop-while #(or (str/blank? %) (str/starts-with? % "#!")))
                     (take-while #(or (str/blank? %) (re-find #"^\s*[;#]" %)))
                     (str/join "\n"))
        python-doc (second (re-find #"(?s)^\s*(?:#![^\n]*\n\s*)?(?:#[^\n]*\n\s*)*[uUrR]?(?:\"\"\"|''')(.*?)(?:\"\"\"|''')" source))
        ns-doc (second (re-find #"(?s)\(ns\s+[^\s()]+\s+\"((?:\\.|[^\"\\])*)\"" source))]
    (not-empty (if (seq (clean-comment leading)) (clean-comment leading) (clean-comment (or python-doc ns-doc ""))))))

(defn header-description
  "返回 Markdown 格式的文件说明。Java/Kotlin 合并前导区里的所有注释段（如版权头 + import 后的类说明）。"
  [source]
  (let [source (-> source (str/replace #"^\uFEFF" "") (str/replace #"\r\n?" "\n"))
        texts (distinct (keep segment-text (prelude-segments source)))]
    (if (seq texts)
      (str/join "\n\n" texts)
      (script-description source))))
