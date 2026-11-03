;; 职责：只读已有解释与链路，按目标筛选并检查代码锚点的新鲜度。
;; 核心入口：读取上下文引用（references）。

(ns arch-view.context.records
  (:require [arch-view.anchor :as anchor]
            [arch-view.cli-support :as support]
            [arch-view.workspace :as workspace]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(declare ^:private read-records ^:private related-target? ^:private related-anchor?
         ^:private note-matches? ^:private trace-matches? ^:private check-record
         ^:private checked-anchor ^:private freshness ^:private load-record)

(defn references [ws resolved]
  (let [notes (read-records ws "notes") traces (read-records ws "traces")]
    {:notes (mapv #(check-record ws % (:anchors %))
                  (filter #(note-matches? ws resolved %) (:records notes)))
     :traces (mapv #(check-record ws % (keep :anchor (:steps %)))
                   (filter #(trace-matches? ws resolved %) (:records traces)))
     :warnings (into (:warnings notes) (:warnings traces))}))

;; ===== 私有方法 =====

(defn- load-record [file]
  (try
    (let [record (support/read-json-file file)
          filename (.getName ^java.io.File file)]
      (when-not (and (support/valid-id? (:id record)) (= filename (str (:id record) ".json")))
        (throw (ex-info "id 必须有效且与文件名一致" {})))
      {:record record})
    (catch Exception ex {:warning (str (.getName ^java.io.File file) "：" (.getMessage ex))})))

(defn- read-records [ws directory]
  (let [dir (io/file (workspace/data-dir ws) directory)
        files (->> (.listFiles dir)
                   (filter #(and (.isFile ^java.io.File %) (str/ends-with? (.getName ^java.io.File %) ".json")))
                   (sort-by #(.getName ^java.io.File %)))
        results (mapv load-record files)]
    {:records (vec (keep :record results)) :warnings (vec (keep :warning results))}))

(defn- related-target? [ws resolved target]
  (try
    (let [other (workspace/resolve-target ws target)
          members (set (:modules resolved))]
      (boolean (some members (:modules other))))
    (catch clojure.lang.ExceptionInfo _ false)))

(defn- related-anchor? [ws resolved entry]
  (contains? (set (:modules resolved)) (workspace/module-for-path ws (:path entry))))

(defn- note-matches? [ws resolved note]
  (boolean (or (some #(related-target? ws resolved %) (:targets note))
               (some #(related-anchor? ws resolved %) (:anchors note)))))

(defn- trace-matches? [ws resolved trace]
  (boolean (some #(related-anchor? ws resolved (:anchor %)) (:steps trace))))

(defn- checked-anchor [ws entry]
  (try (anchor/check-anchor ws entry)
       (catch Exception ex (assoc entry :status "stale" :error (.getMessage ex)))))

(defn- freshness [checked]
  (if (seq checked)
    (:status (apply max-key #(get anchor/status-rank (:status %) 2) checked))
    "unanchored"))

(defn- check-record [ws record anchors]
  (let [checked (mapv #(checked-anchor ws %) anchors)]
    (assoc (select-keys record [:id :title :question :targets])
           :status (freshness checked) :anchors checked)))
