;; 职责：管理 ACP 会话、并发与空闲回收，复用 Agent 原生历史并保留系统只读隔离。
;; 核心入口：create-manager、ask!、cancel!、close!。

(ns arch-view.context.sessions
  (:require [arch-view.context.acp :as acp]
            [arch-view.context.provider :as provider]
            [arch-view.context.sandbox :as sandbox]
            [arch-view.context.session-store :as store])
  (:import [java.time Instant]
           [java.util UUID]
           [java.util.concurrent Executors ScheduledExecutorService ThreadFactory TimeUnit]
           [java.util.concurrent.atomic AtomicBoolean]))

(declare close! reap! release-entry! acquire-entry!)

(def ^:private idle-ms (* 30 60 1000))
(def ^:private max-active 4)

(defn create-manager
  "网页服务独占一个管理器；空闲半小时释放进程，已保存的历史仍可恢复。"
  []
  (let [executor (Executors/newSingleThreadScheduledExecutor
                  (reify ThreadFactory
                    (newThread [_ runnable]
                      (doto (Thread. runnable "arch-view-acp-reaper") (.setDaemon true)))))
        manager {:sessions (atom {}) :lock (Object.) :closed (atom false) :executor executor}]
    (.scheduleWithFixedDelay ^ScheduledExecutorService executor
                             ^Runnable #(try (reap! manager) (catch Exception _ nil))
                             60 60 TimeUnit/SECONDS)
    manager))

(defn close!
  "结束所有进程并清理认证副本；已完成轮次的原生历史保留在私有状态目录。"
  [manager]
  (locking (:lock manager)
    (reset! (:closed manager) true)
    (.shutdownNow ^ScheduledExecutorService (:executor manager))
    (doseq [[id _] @(:sessions manager)] (release-entry! manager id))))

(defn- release-entry! [manager id]
  (when-let [{:keys [connection isolation]} (get @(:sessions manager) id)]
    (swap! (:sessions manager) dissoc id)
    (try (acp/close! connection)
         (finally ((:cleanup isolation))))))

(defn- reap! [manager]
  (locking (:lock manager)
    (doseq [[id {:keys [busy touched]}] @(:sessions manager)]
      (when (and (not (.get ^AtomicBoolean busy))
                 (> (- (System/currentTimeMillis) @touched) idle-ms))
        (release-entry! manager id)))))

(defn- make-room! [manager]
  (when (>= (count @(:sessions manager)) max-active)
    (if-let [[id _] (->> @(:sessions manager)
                         (remove (fn [[_ entry]] (.get ^AtomicBoolean (:busy entry))))
                         (sort-by (fn [[_ entry]] @(:touched entry))) first)]
      (release-entry! manager id)
      (throw (ex-info "已有四个 AI 会话正在作答，请稍后再试" {:status 429})))))

(defn- metadata [target upstream-id id]
  (let [now (str (Instant/now))]
    {:id id :agentSessionId upstream-id :target target :provider "pi-acp"
     :createdAt now :updatedAt now :history []}))

(defn- check-target! [target data]
  (when-not (= target (:target data))
    (throw (ex-info "这个会话属于另一个目标，请为当前模块新建对话" {:status 409}))))

(defn- connect! [ws isolation callback]
  (let [{:keys [command timeout]} (provider/configuration ws)
        prepared (assoc (provider/prepare ws (provider/parse-command command) (:directory isolation))
                        :directory (:directory isolation))
        conn (acp/open! {:args ((:wrap isolation) (:args prepared))
                         :env (merge (:env isolation) (:env prepared)) :cwd (str (:root ws))
                         :timeout timeout :on-update #(when-let [f @callback] (f %))})]
    [prepared conn timeout]))

(defn- attach-session! [conn saved cwd timeout]
  (if saved
    (do (acp/load-session! conn (:agentSessionId saved) cwd timeout)
        (:agentSessionId saved))
    (acp/new-session! conn cwd timeout)))

(defn- open-entry! [ws target id restore?]
  (let [isolation (sandbox/open! ws)
        connection (atom nil)]
    (try
      (let [callback (atom nil)
            [prepared conn timeout] (connect! ws isolation callback)
            _ (reset! connection conn)
            saved (when restore? (store/restore! ws id prepared))
            _ (when saved (check-target! target saved))
            upstream (attach-session! conn saved (str (:root ws)) timeout)]
        (when-not (string? upstream)
          (throw (ex-info "ACP 未返回有效的会话标识" {:status 502})))
        {:connection conn :isolation isolation :prepared prepared :root (str (:root ws))
         :data (atom (or saved (metadata target upstream id))) :callback callback
         :busy (AtomicBoolean. false) :touched (atom (System/currentTimeMillis))})
      (catch Exception ex
        (try (when-let [conn @connection] (acp/close! conn))
             (finally ((:cleanup isolation))))
        (throw ex)))))

(defn- acquire-entry! [manager ws target session-id]
  (locking (:lock manager)
    (when @(:closed manager)
      (throw (ex-info "AI 服务正在关闭" {:status 503})))
    (let [id (or session-id (str (UUID/randomUUID)))
          entry (or (get @(:sessions manager) id)
                    (do (make-room! manager)
                        (let [entry (open-entry! ws target id (some? session-id))]
                          (swap! (:sessions manager) assoc id entry)
                          entry)))]
      (when-not (= (:root entry) (str (:root ws)))
        (throw (ex-info "会话不属于当前项目" {:status 404})))
      (check-target! target @(:data entry))
      (when-not (.compareAndSet ^AtomicBoolean (:busy entry) false true)
        (throw (ex-info "这个 AI 会话正在作答，请等待完成或先取消" {:status 409})))
      [id entry])))

(defn- complete! [ws id entry question anchors result]
  (let [turn {:question question :answer (:answer result) :anchors anchors}
        data (-> @(:data entry)
                 (update :history conj turn)
                 (assoc :updatedAt (str (Instant/now))))]
    (store/save! ws id data (:prepared entry))
    (reset! (:data entry) data)
    (merge result {:sessionId id :provider "pi-acp"
                   :configOptions (acp/config-options (:connection entry))
                   :sandbox {:kind (get-in entry [:isolation :kind]) :readOnly true}})))

(defn- ask-with-manager! [manager ws target markdown question anchors options]
  (let [[id entry] (acquire-entry! manager ws target (:session-id options))
        timeout (:timeout (provider/configuration ws))]
    (reset! (:callback entry) (:on-update options))
    (try
      (when-let [f (:on-session options)] (f {:sessionId id}))
      (complete! ws id entry question anchors
                 (acp/prompt! (:connection entry) (:agentSessionId @(:data entry)) markdown timeout))
      (catch Exception ex
        ;; 超时或连接损坏后只保留上个已保存轮次，恢复时从原生快照重新连接。
        (locking (:lock manager) (release-entry! manager id))
        (throw ex))
      (finally
        (reset! (:callback entry) nil)
        (reset! (:touched entry) (System/currentTimeMillis))
        (.set ^AtomicBoolean (:busy entry) false)))))

(defn ask!
  "续聊只使用服务端保存的会话；请求方不能指定启动命令、文件路径或模型历史。"
  [ws target markdown question anchors options]
  (if-let [manager (:agent-sessions ws)]
    (ask-with-manager! manager ws target markdown question anchors options)
    (let [manager (create-manager)]
      (try (ask-with-manager! manager ws target markdown question anchors options)
           (finally (close! manager))))))

(defn- config-payload [id entry]
  {:sessionId id :configOptions (acp/config-options (:connection entry))})

(defn ensure!
  "打开或恢复 ACP 进程并返回当前模型等选择器，不发起提问。"
  [ws target session-id]
  (let [manager (:agent-sessions ws)]
    (when-not manager (throw (ex-info "当前未启用 ACP 会话" {:status 409})))
    (locking (:lock manager)
      (when @(:closed manager) (throw (ex-info "AI 服务正在关闭" {:status 503})))
      (let [id (or session-id (str (UUID/randomUUID)))
            entry (or (get @(:sessions manager) id)
                      (do (make-room! manager)
                          (let [entry (open-entry! ws target id (some? session-id))]
                            (swap! (:sessions manager) assoc id entry)
                            entry)))]
        (when-not (= (:root entry) (str (:root ws)))
          (throw (ex-info "会话不属于当前项目" {:status 404})))
        (check-target! target @(:data entry))
        (config-payload id entry)))))

(defn set-config!
  "在已打开的会话上切换模型或思考强度；作答过程中拒绝修改。"
  [ws id config-id value]
  (let [manager (:agent-sessions ws)
        entry (when manager (get @(:sessions manager) id))]
    (when-not (and entry (= (:root entry) (str (:root ws))))
      (throw (ex-info "没有已打开的 AI 会话" {:status 404})))
    (when (.get ^AtomicBoolean (:busy entry))
      (throw (ex-info "请等待当前回答完成后再切换模型" {:status 409})))
    {:sessionId id
     :configOptions (acp/set-config-option! (:connection entry)
                                            (:agentSessionId @(:data entry)) config-id value)}))

(defn cancel! [ws id]
  (let [manager (:agent-sessions ws)
        entry (when manager (get @(:sessions manager) id))]
    (when-not (and entry (= (:root entry) (str (:root ws))))
      (throw (ex-info "没有正在运行的 AI 会话" {:status 404})))
    (acp/cancel! (:connection entry) (:agentSessionId @(:data entry)))
    {:cancelled true :sessionId id}))

(defn close-session! [ws id]
  (when-let [manager (:agent-sessions ws)]
    (locking (:lock manager)
      (when-let [entry (get @(:sessions manager) id)]
        (when (.get ^AtomicBoolean (:busy entry))
          (throw (ex-info "请先取消正在作答的会话" {:status 409})))
        (release-entry! manager id))))
  {:closed true :sessionId id})
