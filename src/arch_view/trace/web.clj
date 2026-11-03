;; 职责：提供链路列表和完整逐跳校验的网页数据接口。
;; 核心入口：路由表（routes）；单条列表摘要（list-entry）。

(ns arch-view.trace.web
  (:require [arch-view.trace.store :as store]
            [arch-view.trace.verify :as verify]))

(defn list-entry
  "坏文件只影响当前条目，列表其余链路仍可浏览。"
  [ws id]
  (try
    (let [trace (store/read-trace ws id)
          {:keys [summary verdict]} (verify/verify ws trace)]
      {:id id :title (:title trace) :question (:question trace)
       :stepCount (count (:steps trace)) :summary summary :verdict verdict})
    (catch Exception ex
      {:id id :error (.getMessage ex)})))

(def routes
  {["GET" "/api/traces"] (fn [state _params]
                           (mapv #(list-entry state %) (store/list-ids state)))
   ["GET" "/api/trace"] (fn [state params]
                          (verify/verify state (store/read-trace state (get params "id"))))})
