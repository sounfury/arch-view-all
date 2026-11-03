;; 职责：在强制只读沙箱中执行本机 AI 命令，传入上下文包并限制时间和输出。
;; 核心入口：同步提问（ask!）。

(ns arch-view.context.agent
  (:require [arch-view.anchor :as anchor]
            [arch-view.context.pack :as pack]
            [arch-view.context.provider :as provider]
            [arch-view.context.sandbox :as sandbox]
            [arch-view.context.sessions :as sessions]
            [clojure.string :as str])
  (:import [java.io ByteArrayOutputStream InputStream]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent Callable ExecutionException Executors ExecutorService Future ThreadFactory TimeUnit TimeoutException]
           [java.util.concurrent.atomic AtomicLong]))

(declare ^:private execute! ^:private sandboxed-answer!
         ^:private read-output ^:private stop-process! ^:private submit ^:private remaining
         ^:private await-task ^:private write-input ^:private check-exit ^:private start-process!)

(defn ask!
  "只接受 ws 的全局 agent 配置；问题与上下文通过标准输入或 ACP prompt 传入。"
  ([ws target question] (ask! ws target question {}))
  ([ws target question options]
   (let [{:keys [command timeout provider]} (provider/configuration ws)]
     (when (str/blank? question) (throw (ex-info "问题不能为空" {:status 400})))
     (when (and (:session-id options) (not= "pi-acp" provider))
       (throw (ex-info "当前命令不支持 ACP 续聊，请新建对话或配置 pi-acp" {:status 409})))
     (let [context (pack/build ws target {:question question :complexity (:complexity ws)})
           anchors (->> (get-in context [:data :source])
                        (keep (fn [{:keys [path shownLines content]}]
                                (when (and (integer? shownLines) (pos? shownLines) (seq content))
                                  (anchor/make-anchor ws {:path path :startLine 1 :endLine shownLines}))))
                        vec)
           started (System/nanoTime)
           result (if (= "pi-acp" provider)
                    (sessions/ask! ws (:target context) (:markdown context) question anchors options)
                    (sandboxed-answer! ws command (:markdown context) timeout))]
       (assoc result :command command :anchors anchors
              :elapsedMs (quot (- (System/nanoTime) started) 1000000))))))

;; ===== 私有方法 =====

(def ^:private max-output-bytes (* 1024 1024))

(defn- sandboxed-answer! [ws command markdown timeout]
  (let [isolation (sandbox/open! ws)]
    (try
      (let [{:keys [args env provider]} (provider/prepare ws (provider/parse-command command) (:directory isolation))
            wrapped ((:wrap isolation) args)
            answer (execute! ws wrapped (merge (:env isolation) env) markdown timeout)]
        {:answer answer :provider provider :sandbox {:kind (:kind isolation) :readOnly true}})
      (finally ((:cleanup isolation))))))

(defn- stop-process! [^Process process]
  (with-open [children (.descendants (.toHandle process))]
    (.forEach children (reify java.util.function.Consumer
                         (accept [_ child] (.destroyForcibly ^java.lang.ProcessHandle child)))))
  (.destroyForcibly process))

(defn- read-output [^InputStream stream ^AtomicLong total ^Process process]
  (with-open [stream stream out (ByteArrayOutputStream.)]
    (let [buffer (byte-array 8192)]
      (loop []
        (let [n (.read stream buffer)]
          (when (pos? n)
            (when (> (.addAndGet total n) max-output-bytes)
              (stop-process! process)
              (throw (ex-info "本机 AI 输出超过 1 MB 上限，已停止" {:status 502})))
            (.write out buffer 0 n)
            (recur))))
      (.toString out (.name StandardCharsets/UTF_8)))))

(defn- write-input [^Process process markdown]
  (with-open [out (.getOutputStream process)]
    (try (.write out (.getBytes ^String markdown StandardCharsets/UTF_8))
         (catch java.io.IOException _ nil))))

(defn- submit [^ExecutorService executor f]
  (.submit executor ^Callable (reify Callable (call [_] (f)))))

(defn- remaining [deadline]
  (max 1 (- deadline (System/nanoTime))))

(defn- await-task [^Future task deadline]
  (try (.get task (remaining deadline) TimeUnit/NANOSECONDS)
       (catch ExecutionException ex (throw (.getCause ex)))))

(defn- check-exit [^Process process stdout stderr]
  (when-not (zero? (.exitValue process))
    (throw (ex-info (str "本机 AI 命令退出码 " (.exitValue process)
                         (when-not (str/blank? stderr) (str "：" (subs (str/trim stderr) 0 (min 2000 (count (str/trim stderr)))))))
                    {:status (if (= 125 (.exitValue process)) 503 502) :exitCode (.exitValue process)})))
  stdout)

(defn- start-process! [ws args environment]
  (let [builder (doto (ProcessBuilder. ^java.util.List args)
                  (.directory (.toFile ^java.nio.file.Path (:root ws))))
        child-env (.environment builder)]
    (doseq [[key value] environment]
      (if (nil? value) (.remove child-env key) (.put child-env key (str value))))
    (.start builder)))

(defn- execute! [ws args environment markdown timeout]
  (let [executor (Executors/newFixedThreadPool
                  3 (reify ThreadFactory
                      (newThread [_ runnable] (doto (Thread. runnable "arch-view-context-agent") (.setDaemon true)))))
        process (try (start-process! ws args environment)
                     (catch Exception ex
                       (.shutdownNow executor)
                       (throw (ex-info (str "无法启动只读沙箱：" (.getMessage ex)) {:status 503} ex))))
        total (AtomicLong. 0)
        deadline (+ (System/nanoTime) (long (* timeout 1000000000)))
        stdout (submit executor #(read-output (.getInputStream process) total process))
        stderr (submit executor #(read-output (.getErrorStream process) total process))
        input (submit executor #(write-input process markdown))]
    (try
      (when-not (.waitFor process (remaining deadline) TimeUnit/NANOSECONDS)
        (throw (TimeoutException.)))
      (let [out (await-task stdout deadline) err (await-task stderr deadline)]
        (await-task input deadline)
        (check-exit process out err))
      (catch TimeoutException _
        (throw (ex-info (str "本机 AI 超过 " timeout " 秒，已强制停止") {:status 504})))
      (finally
        (stop-process! process)
        (.shutdownNow executor)
        (doseq [stream [(.getInputStream process) (.getErrorStream process) (.getOutputStream process)]]
          (try (.close ^java.io.Closeable stream) (catch java.io.IOException _ nil)))))))
