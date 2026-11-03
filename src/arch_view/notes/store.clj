;; 职责：校验解释草稿，在项目内安全读写 .arch-view/notes JSON 文件并分配唯一编号。
;; 核心入口：校验草稿（validate-draft）；保存解释（write-note!）；读取解释（read-note）。

(ns arch-view.notes.store
  (:require [arch-view.cli-support :as support]
            [arch-view.workspace :as workspace]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.security MessageDigest]))

(declare ^:private note-file ^:private required-text! ^:private validate-anchor!
         ^:private generated-id ^:private available-id ^:private short-hash)

(defn validate-draft [draft]
  (when-not (map? draft)
    (throw (ex-info "解释草稿必须是 JSON 对象" {:status 400})))
  (doseq [field [:title :body]] (required-text! (get draft field) (name field)))
  (when-not (and (sequential? (:targets draft)) (seq (:targets draft)))
    (throw (ex-info "解释至少需要一个 targets 目标" {:status 400})))
  (doseq [target (:targets draft)] (required-text! target "targets 中的目标"))
  (when (and (some? (:anchors draft)) (not (sequential? (:anchors draft))))
    (throw (ex-info "anchors 必须是数组" {:status 400})))
  (doseq [item (:anchors draft)] (validate-anchor! item))
  (when (contains? draft :id)
    (when-not (support/valid-id? (:id draft))
      (throw (ex-info "解释 id 只允许小写字母、数字和连字符，长度为 1–64" {:status 400}))))
  draft)

(defn read-note [ws id]
  (let [note (support/read-json-file (note-file ws id))]
    (when-not (= id (:id note))
      (throw (ex-info "解释 id 与文件名不一致" {:status 400})))
    note))

(defn list-notes [ws]
  (let [dir (io/file (workspace/data-dir ws) "notes")]
    (->> (.listFiles dir)
         (filter #(.isFile %))
         (map #(.getName %))
         (filter #(str/ends-with? % ".json"))
         sort
         (mapv #(read-note ws (subs % 0 (- (count %) 5)))))))

(defn write-note!
  ([ws note] (write-note! ws note false))
  ([ws note force?]
   (validate-draft note)
   (locking write-note!
     (let [id (or (:id note) (available-id ws (generated-id (:title note))))
           file (note-file ws id)
           saved (assoc note :id id)]
       (when (and (.exists file) (not force?))
         (throw (ex-info (str "解释已存在：" id "；使用 --force 覆盖") {:status 409})))
       (support/write-json-file! file saved)
       saved))))

;; ===== 私有方法 =====

(defn- required-text! [value field]
  (when-not (and (string? value) (not (str/blank? value)))
    (throw (ex-info (str "解释缺少有效的 " field) {:status 400}))))

(defn- validate-anchor! [item]
  (when-not (map? item)
    (throw (ex-info "锚点必须是对象" {:status 400})))
  (required-text! (:path item) "锚点 path")
  (when-not (and (integer? (:startLine item)) (pos? (:startLine item)))
    (throw (ex-info "锚点需要正整数 startLine" {:status 400}))))

(defn- note-file [ws id]
  (when-not (support/valid-id? id)
    (throw (ex-info "无效的解释 id" {:status 400})))
  (let [dir (io/file (workspace/data-dir ws) "notes")
        expected (.normalize (.toAbsolutePath (.toPath dir)))
        file (.getCanonicalFile (io/file dir (str id ".json")))]
    (when-not (.startsWith (.toPath file) expected)
      (throw (ex-info "解释文件必须位于项目的 .arch-view/notes 内" {:status 400})))
    file))

(defn- short-hash [title]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes ^String title "UTF-8"))]
    (subs (apply str (map #(format "%02x" (bit-and % 0xff)) digest)) 0 10)))

(defn- generated-id [title]
  (let [slug (-> title str/lower-case (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-|-$" ""))]
    (if (seq slug) (subs slug 0 (min 64 (count slug))) (str "note-" (short-hash title)))))

(defn- available-id [ws base]
  (loop [n 1]
    (let [suffix (if (= n 1) "" (str "-" n))
          id (str (subs base 0 (min (count base) (- 64 (count suffix)))) suffix)]
      (if (.exists (note-file ws id)) (recur (inc n)) id))))
