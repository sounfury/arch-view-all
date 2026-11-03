;; 职责：校验链路草稿，并在项目数据目录中安全读写链路 JSON。
;; 核心入口：草稿校验（validate-draft!）；列出、读取、写入（list-ids、read-trace、write-trace!）。

(ns arch-view.trace.store
  (:require [arch-view.cli-support :as cli-support]
            [arch-view.workspace :as workspace]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(declare ^:private trace-file ^:private require-text! ^:private validate-step!
         ^:private validate-lines! ^:private require-id!)

(defn validate-draft!
  "校验必填字段和逐步格式；成功时原样返回草稿。"
  [draft]
  (when-not (map? draft) (throw (ex-info "链路草稿必须是 JSON 对象" {:status 400})))
  (require-text! (:id draft) "链路缺少 id")
  (require-id! (:id draft))
  (require-text! (:title draft) "链路缺少 title")
  (when-not (and (sequential? (:steps draft)) (seq (:steps draft)))
    (throw (ex-info "链路 steps 必须至少包含 1 步" {:status 400})))
  (doseq [[index step] (map-indexed vector (:steps draft))]
    (validate-step! (inc index) step))
  draft)

(defn list-ids
  "按 id 排序列出链路文件；忽略不符合 id 契约的文件名。"
  [ws]
  (->> (.listFiles (io/file (workspace/data-dir ws) "traces"))
       (filter #(.isFile ^java.io.File %))
       (map #(.getName ^java.io.File %))
       (filter #(str/ends-with? % ".json"))
       (map #(subs % 0 (- (count %) 5)))
       (filter cli-support/valid-id?) sort vec))

(defn read-trace [ws id]
  (let [trace (validate-draft! (cli-support/read-json-file (trace-file ws id)))]
    (when-not (= id (:id trace))
      (throw (ex-info "链路 id 与文件名不一致" {:status 400})))
    trace))

(defn list-traces [ws]
  (mapv #(read-trace ws %) (list-ids ws)))

(defn write-trace!
  "保存已准备的链路；覆盖现有 id 必须显式传 true。"
  ([ws trace] (write-trace! ws trace false))
  ([ws trace force?]
   (validate-draft! trace)
   (let [file (trace-file ws (:id trace))]
     (when (and (.exists file) (not force?))
       (throw (ex-info (str "链路已存在：" (:id trace) "；使用 --force 覆盖") {:status 409})))
     (cli-support/write-json-file! file trace)
     trace)))

;; ===== 私有方法 =====

(defn- require-id! [id]
  (when-not (cli-support/valid-id? id)
    (throw (ex-info "链路 id 只允许小写字母、数字和连字符，长度为 1–64，且以字母或数字开头" {:status 400}))))

(defn- trace-file [ws id]
  (require-id! id)
  (let [directory (.getCanonicalFile (io/file (workspace/data-dir ws) "traces"))
        file (.getCanonicalFile (io/file directory (str id ".json")))]
    (when-not (.startsWith (.toPath file) (.toPath directory))
      (throw (ex-info "链路文件必须位于 traces 目录内" {:status 400})))
    file))

(defn- require-text! [value message]
  (when-not (and (string? value) (not (str/blank? value)))
    (throw (ex-info message {:status 400}))))

(defn- validate-lines! [prefix {:keys [startLine endLine]}]
  (when-not (and (integer? startLine) (pos? startLine))
    (throw (ex-info (str prefix "缺少有效的 anchor.startLine（必须为正整数）") {:status 400})))
  (when (some? endLine)
    (when-not (and (integer? endLine) (<= startLine endLine))
      (throw (ex-info (str prefix "的 anchor.endLine 必须是不小于 startLine 的整数") {:status 400})))))

(defn- validate-step! [number {:keys [title anchor via] :as step}]
  (let [prefix (str "第 " number " 步")]
    (require-text! title (str prefix "缺少 title"))
    (require-text! (:path anchor) (str prefix "缺少 anchor.path"))
    (validate-lines! prefix anchor)
    (when (contains? step :via)
      (when-not (#{"call" "http" "event" "db" "config" "return" "other"} via)
        (throw (ex-info (str prefix "的 via 只能是 call、http、event、db、config、return、other")
                        {:status 400}))))))
