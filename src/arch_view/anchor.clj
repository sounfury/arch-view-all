;; 职责：生成和检查代码锚点（文件 + 行区间 + 内容哈希），判断解释与链路引用的代码是否仍然新鲜、移动或已过期。
;; 核心入口：生成锚点（make-anchor）；检查锚点（check-anchor）。

(ns arch-view.anchor
  (:require [arch-view.workspace :as workspace]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.file Path]
           [java.security MessageDigest]))

(declare ^:private read-lines ^:private range-hash ^:private find-moved ^:private anchor-file
         ^:private line-number)

(defn make-anchor
  "补上内容哈希并规范路径；文件不存在、越出项目或行号越界时抛出 400。"
  [ws {:keys [path startLine endLine symbol]}]
  (let [file (anchor-file ws path)
        start (line-number startLine "startLine")
        end (line-number (or endLine startLine) "endLine")]
    (when-not (.isFile file) (throw (ex-info (str "锚点文件不存在：" path) {:status 400})))
    (let [lines (read-lines file)]
      (when-not (<= 1 start end (count lines))
        (throw (ex-info (str "锚点行号越界：" path ":" start "-" end "（共 " (count lines) " 行）")
                        {:status 400})))
      (cond-> {:path (workspace/relative ws file) :startLine start :endLine end
               :hash (range-hash lines start end)}
        (and symbol (not (str/blank? (str symbol)))) (assoc :symbol (str symbol))))))

(defn check-anchor
  "返回原锚点并附 :status（fresh、moved、stale、missing）；moved 时附当前行号。"
  [ws {:keys [startLine endLine hash] :as anchor}]
  (let [file (try (anchor-file ws (:path anchor)) (catch Exception _ nil))]
    (if-not (and file (.isFile file))
      (assoc anchor :status "missing")
      (let [lines (read-lines file)
            in-range? (<= 1 startLine endLine (count lines))]
        (cond
          (and in-range? (= hash (range-hash lines startLine endLine))) (assoc anchor :status "fresh")
          :else (if-let [start (find-moved lines (inc (- endLine startLine)) hash startLine)]
                  (assoc anchor :status "moved" :currentStartLine start
                         :currentEndLine (+ start (- endLine startLine)))
                  (assoc anchor :status "stale")))))))

(defn relocate
  "把 moved 的锚点改到当前行号，其它状态原样返回（去掉检查附带的键）。"
  [anchor checked]
  (let [base (dissoc anchor :status :currentStartLine :currentEndLine)]
    (if (= "moved" (:status checked))
      (assoc base :startLine (:currentStartLine checked) :endLine (:currentEndLine checked))
      base)))

(def status-rank
  "锚点状态从好到坏的排序，供汇总“最差状态”使用。"
  {"fresh" 0 "moved" 1 "stale" 2 "missing" 3})

;; ===== 私有方法 =====

(defn- line-number [value field]
  (let [n (cond (integer? value) value
                (and (string? value) (re-matches #"\d+" value)) (Long/parseLong value)
                :else nil)]
    (when-not n (throw (ex-info (str "锚点缺少有效的 " field) {:status 400})))
    (long n)))

(defn- anchor-file [{:keys [root]} path]
  (when (str/blank? (str path)) (throw (ex-info "锚点缺少 path" {:status 400})))
  (let [file (io/file path)
        file (.getCanonicalFile (if (.isAbsolute file) file (io/file (.toFile ^Path root) (str path))))]
    (when-not (.startsWith (.toPath file) ^Path root)
      (throw (ex-info "锚点文件必须位于当前项目内" {:status 400})))
    file))

(defn- read-lines [file]
  (-> (slurp file :encoding "UTF-8") (str/replace "\r\n" "\n") (str/split #"\n" -1)))

(defn- range-hash [lines start end]
  (let [text (str/join "\n" (map str/trimr (subvec (vec lines) (dec start) end)))
        digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes ^String text "UTF-8"))]
    (str "sha256:" (apply str (map #(format "%02x" (bit-and % 0xff)) digest)))))

(defn- find-moved
  "在文件中找哈希相同的等长区间，优先离原位置最近的。"
  [lines size hash original]
  (let [total (count lines)
        starts (sort-by #(Math/abs (long (- % original))) (range 1 (+ 2 (- total size))))]
    (first (filter #(= hash (range-hash lines % (+ % size -1))) starts))))
