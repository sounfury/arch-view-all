;; 职责：通用 ACP v1 stdio JSON-RPC 连接；会话选择与文件隔离由调用方负责。
;; 核心入口：open!、new-session!、prompt!、cancel!、close!；timeout 单位为秒。
(ns arch-view.context.acp
  (:require [arch-view.web.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io ByteArrayOutputStream Closeable InputStream]
           [java.nio ByteBuffer]
           [java.nio.charset CodingErrorAction StandardCharsets]
           [java.util.concurrent ArrayBlockingQueue ExecutorService Executors ThreadFactory TimeUnit]
           [java.util.concurrent.atomic AtomicLong]))

(declare ^:private fail! ^:private send! ^:private dispatch! ^:private request!)

(def ^:private max-output-bytes (* 1024 1024))
(def ^:private json-token
  #"\"(?:[^\"\\\x00-\x1f]|\\(?:[\"\\/bfnrt]|u[0-9a-fA-F]{4}))*+\"|true|false|null|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?(?![0-9.eE+\-])|[{}\[\],:]|[ \t\r\n]+")

(defn- failure [reason status]
  (ex-info (str "ACP " (name reason)) {:status status :reason reason}))

(defn- invalid! [] (throw (failure :invalid-protocol 502)))

(defn- quietly [f]
  (try (f) (catch Exception _ nil)))

(defn- stop-process! [^Process process]
  (quietly #(with-open [children (.descendants (.toHandle process))]
              (.forEach children
                        (reify java.util.function.Consumer
                          (accept [_ child] (.destroyForcibly ^java.lang.ProcessHandle child))))))
  (.destroyForcibly process)
  (quietly #(.waitFor process 200 TimeUnit/MILLISECONDS)))

(defn- dispose! [{:keys [process executor]}]
  (stop-process! process)
  (.shutdownNow ^ExecutorService executor)
  (doseq [stream [(.getInputStream ^Process process) (.getErrorStream ^Process process)
                  (.getOutputStream ^Process process)]]
    (quietly #(.close ^Closeable stream))))

(defn- fail! [conn error]
  (let [pending (locking (:state conn)
                  (when-not (:failure @(:state conn))
                    (let [pending (:pending @(:state conn))]
                      (swap! (:state conn) assoc :failure error :pending {} :active nil)
                      pending)))]
    (when pending
      (doseq [[_ reply] pending] (deliver reply {:error error}))
      (dispose! conn)))
  error)

(defn close!
  "幂等关闭连接，终止进程及其后代，唤醒正在等待的调用。"
  [conn]
  (fail! conn (failure :closed 503))
  nil)

(defn- ensure-open! [conn]
  (when-let [error (:failure @(:state conn))] (throw error)))

(defn- process-error [{:keys [process]}]
  (let [^Process process process]
    (if (.isAlive process)
      (failure :eof 502)
      (let [code (.exitValue process)]
        (ex-info "ACP process exited"
                 {:status (if (= 125 code) 503 502) :reason :process-exit :exitCode code})))))

(defn- charge! [conn n]
  (when (> (.addAndGet ^AtomicLong (:output-bytes conn) n) max-output-bytes)
    (throw (failure :output-limit 502))))

(defn- validate-json-text! [^String text]
  ;; JSON helper 有意宽松；在传输边界拒绝非 JSON 数字、控制字符及过深嵌套。
  (let [matcher (re-matcher json-token text)]
    (loop [offset 0 depth 0]
      (when (< offset (count text))
        (.region matcher offset (count text))
        (when-not (.lookingAt matcher) (invalid!))
        (let [token (.group matcher)
              depth (+ depth (get {"{" 1 "[" 1 "}" -1 "]" -1} token 0))]
          (when (> depth 64) (invalid!))
          (recur (.end matcher) depth))))))

(defn- decode-frame [^ByteArrayOutputStream frame]
  (try
    (let [decoder (doto (.newDecoder StandardCharsets/UTF_8)
                    (.onMalformedInput CodingErrorAction/REPORT)
                    (.onUnmappableCharacter CodingErrorAction/REPORT))
          text (str (.decode decoder (ByteBuffer/wrap (.toByteArray frame))))]
      (validate-json-text! text)
      (json/read-json text))
    (catch Exception _ (invalid!))))

(defn- consume-frames! [conn ^ByteArrayOutputStream frame buffer n]
  (dotimes [i n]
    (let [b (aget ^bytes buffer i)]
      (if (= 10 b)
        (let [message (decode-frame frame)]
          (.reset frame)
          (dispatch! conn message))
        (.write frame (int b))))))

(defn- read-stdout! [conn]
  (with-open [stream (.getInputStream ^Process (:process conn))
              frame (ByteArrayOutputStream.)]
    (let [buffer (byte-array 8192)]
      (loop []
        (let [n (.read ^InputStream stream buffer)]
          (if (neg? n)
            (do (.waitFor ^Process (:process conn) 100 TimeUnit/MILLISECONDS)
                (throw (process-error conn)))
            (do (charge! conn n)
                (consume-frames! conn frame buffer n)
                (recur))))))))

(defn- read-stderr! [conn]
  ;; 持续消费但不保存日志；包括凭据在内的 stderr 不进入异常或返回值。
  (with-open [stream (.getErrorStream ^Process (:process conn))]
    (let [buffer (byte-array 8192)]
      (loop []
        (let [n (.read ^InputStream stream buffer)]
          (when (pos? n) (charge! conn n) (recur)))))))

(defn- send! [conn message]
  (ensure-open! conn)
  (when-not (.offer ^ArrayBlockingQueue (:outbound conn) message)
    (throw (fail! conn (failure :write-queue-limit 502))))
  nil)

(defn- write-messages! [conn]
  (let [stream (.getOutputStream ^Process (:process conn))]
    (loop []
      (let [message (.take ^ArrayBlockingQueue (:outbound conn))
            bytes (.getBytes (str (json/write-json message) "\n") StandardCharsets/UTF_8)]
        (.write stream bytes)
        (.flush stream)
        (recur)))))

(defn- permission-outcome [params]
  (if-let [option (some #(when (and (#{"reject_once" "reject_always"} (get % "kind"))
                                   (string? (get % "optionId"))) %) (get params "options"))]
    {:outcome {:outcome "selected" :optionId (get option "optionId")}}
    {:outcome {:outcome "cancelled"}}))

(defn- answer-request! [conn message]
  (let [{:strs [id method params]} message
        reply (if (= "session/request_permission" method)
                {:result (permission-outcome params)}
                {:error {:code -32601 :message "Method not found"}})]
    (send! conn (merge {:jsonrpc "2.0" :id id} reply))))

(defn- update-answer [active session-update]
  (case (get session-update "sessionUpdate")
    "agent_message_chunk"
    (let [content (get session-update "content")]
      (when-not (map? content) (invalid!))
      (if (= "text" (get content "type"))
        (do
          (when-not (string? (get content "text")) (invalid!))
          (.append ^StringBuilder (:answer active) ^String (get content "text"))
          active)
        active))
    ("tool_call" "tool_call_update") (update active :toolEvents conj session-update)
    active))

(defn- accept-update! [conn params]
  (when-not (and (map? params) (string? (get params "sessionId"))
                 (map? (get params "update")) (string? (get-in params ["update" "sessionUpdate"])))
    (invalid!))
  (locking (:state conn)
    (when (= (get params "sessionId") (get-in @(:state conn) [:active :session-id]))
      (swap! (:state conn) update :active update-answer (get params "update"))))
  (when (:on-update conn)
    (when-not (.offer ^ArrayBlockingQueue (:updates conn) params)
      (throw (failure :callback-queue-limit 502)))))

(defn- response-error [error]
  (when-not (and (map? error) (integer? (get error "code")) (string? (get error "message")))
    (invalid!))
  (ex-info "ACP RPC error" {:status 502 :reason :rpc-error :rpcCode (get error "code")}))

(defn- accept-response! [conn message]
  (when (= (contains? message "result") (contains? message "error")) (invalid!))
  (let [result (if (contains? message "error")
                 {:error (response-error (get message "error"))}
                 {:result (get message "result")})
        id (get message "id")]
    (locking (:state conn)
      (let [reply (get-in @(:state conn) [:pending id])]
        (when-not reply (invalid!))
        (swap! (:state conn) update :pending dissoc id)
        (deliver reply result)))))

(defn- valid-id? [id] (or (integer? id) (string? id)))

(defn- dispatch! [conn message]
  (when-not (and (map? message) (= "2.0" (get message "jsonrpc"))) (invalid!))
  (when (contains? message "id")
    (when-not (valid-id? (get message "id")) (invalid!)))
  (if (contains? message "method")
    (do
      (when-not (and (string? (get message "method"))
                    (not (contains? message "result")) (not (contains? message "error")))
        (invalid!))
      (if (contains? message "id")
        (answer-request! conn message)
        (when (= "session/update" (get message "method"))
          (accept-update! conn (get message "params")))))
    (accept-response! conn message)))

(defn- run-worker! [conn f]
  (.execute ^ExecutorService (:executor conn)
            ^Runnable
            (fn []
              (try (f)
                   (catch InterruptedException _ nil)
                   (catch Exception ex
                     (fail! conn (if (instance? clojure.lang.ExceptionInfo ex)
                                   ex (process-error conn))))))))

(defn- start-workers! [conn]
  (run-worker! conn #(read-stdout! conn))
  (run-worker! conn #(read-stderr! conn))
  (run-worker! conn #(write-messages! conn))
  (run-worker! conn #(do (.waitFor ^Process (:process conn)) (fail! conn (process-error conn))))
  (when-let [callback (:on-update conn)]
    (run-worker! conn #(loop []
                        (let [params (.take ^ArrayBlockingQueue (:updates conn))]
                          (try (callback params)
                               (catch Exception _ (throw (failure :callback-error 502)))))
                        (recur))))
  conn)

(defn- checked-timeout [timeout]
  (when-not (and (number? timeout) (Double/isFinite (double timeout)) (pos? timeout))
    (throw (failure :invalid-timeout 503)))
  timeout)

(defn- await-reply! [conn reply timeout]
  (let [value (deref reply (long (Math/ceil (* 1000 (checked-timeout timeout)))) ::timeout)]
    (when (= ::timeout value) (throw (fail! conn (failure :timeout 504))))
    (when-let [error (:error value)] (throw error))
    (:result value)))

(defn- request! [conn method params timeout]
  (checked-timeout timeout)
  (let [id (.incrementAndGet ^AtomicLong (:next-id conn)) reply (promise)]
    (locking (:state conn)
      (ensure-open! conn)
      (swap! (:state conn) assoc-in [:pending id] reply))
    (send! conn {:jsonrpc "2.0" :id id :method method :params params})
    (await-reply! conn reply timeout)))

(defn- absolute-cwd [cwd]
  (.getCanonicalPath (io/file (str cwd))))

(defn- start-process! [args env cwd]
  (try
    (when-not (and (sequential? args) (seq args) (every? string? args))
      (throw (failure :invalid-args 503)))
    (let [builder (doto (ProcessBuilder. ^java.util.List (vec args)) (.directory (io/file cwd)))
          environment (.environment builder)]
      (doseq [[key value] env]
        (if (nil? value) (.remove environment key) (.put environment key (str value))))
      (.start builder))
    (catch Exception _ (throw (failure :start-failed 503)))))

(defn- connection [process cwd timeout on-update]
  {:process process :cwd cwd :timeout timeout :on-update on-update
   :state (atom {:pending {}}) :round-lock (Object.)
   :next-id (AtomicLong. 0) :output-bytes (AtomicLong. 0)
   :outbound (ArrayBlockingQueue. 64) :updates (ArrayBlockingQueue. 64)
   :executor (Executors/newFixedThreadPool
               5 (reify ThreadFactory
                   (newThread [_ runnable]
                     (doto (Thread. runnable "arch-view-acp") (.setDaemon true)))))})

(defn open!
  "启动 argv（不经 shell），合并 env（nil 删除变量），协商 ACP v1。
  cwd 默认当前目录，timeout 默认 60 秒。返回含 :initialize、:capabilities 的连接。
  on-update 可选，接收字符串键的 session/update params；独立有界队列按序回调。
  回调异常或队列溢出关闭连接。连接不保存 args/env；调用方负责沙箱。"
  [{:keys [args env cwd timeout on-update] :or {cwd "." timeout 60}}]
  (checked-timeout timeout)
  (let [cwd (absolute-cwd cwd)
        conn (connection (start-process! args env cwd) cwd timeout on-update)]
    (try
      (start-workers! conn)
      (let [result (request! conn "initialize"
                             {:protocolVersion 1
                              :clientCapabilities {:fs {:readTextFile false :writeTextFile false}
                                                   :terminal false}}
                             timeout)]
        (when-not (and (map? result) (= 1 (get result "protocolVersion"))
                       (map? (get result "agentCapabilities" {})))
          (invalid!))
        (assoc conn :initialize result :capabilities (get result "agentCapabilities" {})))
      (catch Exception ex (fail! conn ex) (throw ex)))))

(defn- begin-round! [conn]
  (ensure-open! conn)
  (.set ^AtomicLong (:output-bytes conn) 0))

(defn- config-choice [item]
  (when (and (map? item) (string? (get item "value")) (string? (get item "name")))
    {:value (get item "value") :name (get item "name") :description (get item "description")}))

(defn- config-choices [options]
  (->> options
       (mapcat (fn [item]
                 (if (and (map? item) (get item "group")) (or (get item "options") []) [item])))
       (keep config-choice) vec))

(defn- config-option [item]
  (when (and (map? item) (= "select" (get item "type")) (string? (get item "id"))
             (string? (get item "name")) (string? (get item "currentValue")))
    (let [choices (config-choices (get item "options"))]
      (when (seq choices)
        {:id (get item "id") :name (get item "name") :category (get item "category")
         :type "select" :currentValue (get item "currentValue") :options choices}))))

(defn- remember-config! [conn result]
  (let [options (into [] (keep config-option (get result "configOptions")))]
    (swap! (:state conn) assoc :config-options options)
    options))

(defn config-options
  "最近一次 session/new、session/load 或 set_config_option 返回的选择器。"
  [conn]
  (or (:config-options @(:state conn)) []))

(defn new-session!
  "创建会话并返回 session-id；cwd 规范化为绝对路径，MCP servers 始终为空。
  普通 RPC 按连接串行执行，每轮 stdout/stderr 合计最多 1 MiB。"
  ([conn] (new-session! conn (:cwd conn) (:timeout conn)))
  ([conn cwd] (new-session! conn cwd (:timeout conn)))
  ([conn cwd timeout]
   (locking (:round-lock conn)
     (begin-round! conn)
     (let [result (request! conn "session/new" {:cwd (absolute-cwd cwd) :mcpServers []} timeout)
           session-id (get result "sessionId")]
       (when-not (and (string? session-id) (not (str/blank? session-id)))
         (throw (fail! conn (failure :invalid-protocol 502))))
       (remember-config! conn result)
       session-id))))

(defn load-session!
  "恢复 agent 原生会话；仅在协商的 loadSession=true 时可用。返回 session-id。
  历史 replay 只经 on-update 分发，不聚合为下一轮 prompt 的回答。"
  ([conn session-id] (load-session! conn session-id (:cwd conn) (:timeout conn)))
  ([conn session-id cwd timeout]
   (locking (:round-lock conn)
     (when-not (true? (get (:capabilities conn) "loadSession"))
       (throw (failure :load-session-unsupported 503)))
     (begin-round! conn)
     (let [result (request! conn "session/load"
                            {:sessionId session-id :cwd (absolute-cwd cwd) :mcpServers []} timeout)]
       (when-not (map? result) (throw (fail! conn (failure :invalid-protocol 502))))
       (remember-config! conn result)
       session-id))))

(defn prompt!
  "向已有会话发送 Markdown 文本，返回 :answer、:stopReason 和原始 :toolEvents。
  只聚合 agent_message_chunk 的 text；会话复用由调用方管理。timeout 单位为秒。"
  ([conn session-id markdown] (prompt! conn session-id markdown (:timeout conn)))
  ([conn session-id markdown timeout]
   (locking (:round-lock conn)
     (begin-round! conn)
     (locking (:state conn)
       (swap! (:state conn) assoc :active
              {:session-id session-id :answer (StringBuilder.) :toolEvents []}))
     (try
       (let [result (request! conn "session/prompt"
                              {:sessionId session-id :prompt [{:type "text" :text markdown}]} timeout)]
         (when-not (and (map? result) (string? (get result "stopReason")))
           (throw (fail! conn (failure :invalid-protocol 502))))
         (locking (:state conn)
           (let [active (:active @(:state conn))]
             {:answer (str (:answer active)) :stopReason (get result "stopReason")
              :toolEvents (:toolEvents active)})))
       (finally (locking (:state conn) (swap! (:state conn) dissoc :active)))))))

(defn cancel!
  "异步发送 session/cancel 通知，可与 prompt! 并发调用；不关闭连接。"
  [conn session-id]
  (send! conn {:jsonrpc "2.0" :method "session/cancel" :params {:sessionId session-id}}))

(defn set-config-option!
  "设置当前会话的模型或思考强度等选择器，返回完整 configOptions。"
  [conn session-id config-id value]
  (when (or (str/blank? config-id) (nil? value))
    (throw (ex-info "缺少要修改的配置项或取值" {:status 400})))
  (remember-config! conn
                    (request! conn "session/set_config_option"
                              {:sessionId session-id :configId config-id :value value}
                              (:timeout conn))))
