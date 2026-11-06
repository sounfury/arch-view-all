;; 职责：规范解释目标与锚点，汇总过期状态，并按模块和包匹配已有解释。
;; 核心入口：准备草稿（prepare）；检查解释（check）；目标匹配（matching）。

(ns arch-view.notes.core
  (:require [arch-view.anchor :as anchor]
            [arch-view.notes.store :as store]
            [arch-view.workspace :as workspace]
            [clojure.string :as str])
  (:import [java.time Instant]))

(declare ^:private resolve-draft-target ^:private target-missing? ^:private worst-status
         ^:private matches-target? ^:private ancestor? ^:private newest-first ^:private prepare-anchor)

(defn prepare [ws draft]
  (store/validate-draft draft)
  (-> draft
      (assoc :targets (mapv #(resolve-draft-target ws %) (:targets draft))
             :anchors (mapv #(prepare-anchor ws %) (:anchors draft))
             :createdAt (or (:createdAt draft) (str (Instant/now)))
             :commit (workspace/git-head ws)
             :author (or (:author draft) "ai")
             :source (or (:source draft) "manual"))))

(defn check [ws note]
  (let [anchors (mapv #(anchor/check-anchor ws %) (:anchors note))]
    (assoc note :anchors anchors :status (worst-status anchors)
           :targetMissing (boolean (some #(target-missing? ws %) (:targets note))))))

(defn matching [notes ws target]
  (let [{:keys [kind target]} (workspace/resolve-target ws target)
        matches (filter #(some (partial matches-target? ws kind target) (:targets %)) notes)]
    (->> matches
         (sort newest-first)
         (sort-by #(if (some #{target} (:targets %)) 0 1))
         vec)))

(defn checked-notes
  "按目标匹配或按创建时间降序列出解释，并检查每条的源码锚点和目标。"
  [ws target]
  (let [all (store/list-notes ws)
        selected (if target (matching all ws target) (sort-by :createdAt #(compare %2 %1) all))]
    (mapv #(check ws %) selected)))

(defn fix-moved [note checked]
  (assoc note :anchors (mapv anchor/relocate (:anchors note) (:anchors checked))))

;; ===== 私有方法 =====

(defn- prepare-anchor [ws item]
  (if (:hash item)
    (let [checked (anchor/check-anchor ws item)
          fields (select-keys item [:path :startLine :endLine :hash :symbol])]
      (case (:status checked)
        "fresh" fields
        "moved" (anchor/relocate fields checked)
        (throw (ex-info "引用源码在生成解释后已变化，请重新解释或核对后再保存" {:status 409}))))
    (anchor/make-anchor ws item)))

(defn- resolve-draft-target [ws target]
  (try (:target (workspace/resolve-target ws target))
       (catch clojure.lang.ExceptionInfo ex
         (throw (ex-info (.getMessage ex) (assoc (ex-data ex) :status 400) ex)))))

(defn- target-missing? [ws target]
  (try (workspace/resolve-target ws target) false
       (catch clojure.lang.ExceptionInfo _ true)))

(defn- worst-status [anchors]
  (if (seq anchors)
    (:status (apply max-key #(get anchor/status-rank (:status %)) anchors))
    "unanchored"))

(defn- ancestor? [prefix target]
  (str/starts-with? target (str prefix ".")))

(defn- matches-target? [ws kind target stored]
  (or (= target stored)
      (and (not (contains? (get-in ws [:architecture :graph :nodes]) stored))
           (ancestor? stored target))
      (and (= :package kind) (ancestor? target stored))))

(defn- newest-first [left right]
  (compare (str (:createdAt right)) (str (:createdAt left))))
