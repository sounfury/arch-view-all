;; 职责：提供解释列表、详情和保存接口，返回正文、锚点检查与目标失效状态。
;; 核心入口：HTTP 路由表（routes）。

(ns arch-view.notes.web
  (:require [arch-view.cli-support :as support]
            [arch-view.notes.core :as notes]
            [arch-view.notes.store :as store]))

(declare ^:private save-note!)

(def routes
  {["GET" "/api/notes"] (fn [ws params] (notes/checked-notes ws (get params "target")))
   ["GET" "/api/note"] (fn [ws params] (notes/check ws (store/read-note ws (get params "id"))))
   ["POST" "/api/note"] (fn [ws params] (save-note! ws params))})

;; ===== 私有方法 =====

(defn- save-note! [ws params]
  (let [draft (try (support/read-json-text (get params "body" ""))
                   (catch Exception ex
                     (throw (ex-info "请求体必须是有效的解释草稿 JSON" {:status 400} ex))))
        note (notes/prepare ws draft)
        saved (store/write-note! ws note (= "true" (get params "force")))]
    (notes/check ws saved)))
