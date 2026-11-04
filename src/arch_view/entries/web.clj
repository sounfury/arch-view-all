;; 职责：提供网页入口列表、按入口缓存的方法调用图、出口与另存 trace；规则重新加载时一起失效。
;; 核心入口：HTTP 路由表（routes）。

(ns arch-view.entries.web
  (:require [arch-view.cli-support :as support]
            [arch-view.entries.chain :as chain]
            [arch-view.entries.chain-trace :as chain-trace]
            [arch-view.entries.core :as entries]
            [arch-view.entries.report :as report]
            [arch-view.entries.source-snapshot :as snapshot]
            [arch-view.entries.rules :as rules]))

(declare ^:private bundle ^:private reload! ^:private chain-data ^:private save-chain!)

(def routes
  {["GET" "/api/entries"] (fn [state _] (:data (bundle state)))
   ["POST" "/api/entries/reload"] (fn [state _] (:data (reload! state)))
   ["GET" "/api/entry/chain"] (fn [state params] (chain-data state params))
   ["POST" "/api/entry/chain/save"] (fn [state params] (save-chain! state params))})

;; ===== 私有方法 =====

(defn- entries-data [scanned]
  (let [checked (report/check scanned)]
    {:language (some-> (:language scanned) name)
     :unsupported (boolean (:unsupported scanned))
     :rules (:rules scanned) :entries (:entries scanned) :grouping (:grouping scanned)
     :channels (mapv (fn [{:keys [channel rules count]}] {:label channel :rules rules :count count}) (:channels checked))
     :summary (:summary checked)
     :warnings (mapv #(select-keys % [:code :message :count]) (:warnings checked))}))

(defn- create-bundle [state]
  (if (= :java (get-in state [:architecture :guidance :language]))
    (let [loaded (rules/load-rules state)
          ctx (entries/analysis-context state loaded)
          scanned (entries/scan-with state loaded ctx)]
      {:data (entries-data scanned)
       :graph (:method-graph (meta scanned)) :chains (atom {})})
    {:data (entries-data (entries/scan state))}))

(defn- bundle
  "同一次分析只计算一次入口；有架构绑定时方法图用于分组，否则延迟到首次链路请求。"
  [state]
  (if-let [cache (:entries-cache state)]
    (locking cache (or @cache (reset! cache (create-bundle state))))
    (create-bundle state)))

(defn- reload! [state]
  (if-let [cache (:entries-cache state)]
    (locking cache (reset! cache (create-bundle state)))
    (create-bundle state)))

(defn- chain-data [state params]
  (let [{:keys [data graph chains]} (bundle state)
        id (get params "id")
        entry (some #(when (= id (:id %)) %) (:entries data))
        options {:depth (parse-long (str (get params "depth" 6))) :limit (parse-long (str (get params "limit" 60)))}
        key [id options]]
    (when-not graph (throw (ex-info "入口调用链目前只支持 Java 项目。" {:status 400})))
    (when-not entry (throw (ex-info "入口已不存在，请重新加载入口列表。" {:status 404})))
    (locking chains
      (or (get @chains key)
          (let [result (chain/slice @graph entry options)]
            ;; 深度/节点上限的不同组合最多缓存 64 份，避免反复展开占用无限内存。
            (when (>= (count @chains) 64) (swap! chains dissoc (first (keys @chains))))
            (swap! chains assoc key result)
            result)))))

(defn- save-chain! [state params]
  (let [draft (try (support/read-json-text (get params "body" ""))
                   (catch Exception ex (throw (ex-info "请求体必须是有效 JSON。" {:status 400} ex))))]
    (when-not (and (map? draft) (support/valid-id? (:id draft))
                   (string? (:entryId draft)) (or (nil? (:title draft)) (string? (:title draft))))
      (throw (ex-info "需要有效的 id、entryId 和可选的 title。id 只能用小写字母、数字和连字符，最多 64 字符。" {:status 400})))
    (let [result (chain-data state {"id" (:entryId draft) "depth" (:depth draft 6) "limit" (:limit draft 60)})]
      (snapshot/assert-current! state (:fingerprints (meta result)) (keys (:fingerprints (meta result))))
      (chain-trace/save! state result draft))))
