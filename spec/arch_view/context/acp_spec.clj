;; 职责：通过本地 Python 假 ACP 进程验证真实管道、反向请求、复用及失败清理。
(ns arch-view.context.acp-spec
  (:require [arch-view.context.acp :as acp]
            [arch-view.web.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [speclj.core :refer :all])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def peer-script (.getCanonicalPath (io/file "spec/arch_view/context/fixtures/acp_peer.py")))

(defn peer! [connections mode & [options]]
  (let [conn (acp/open! (merge {:args ["python3" "-u" peer-script mode]
                                :cwd "." :timeout 5} options))]
    (swap! connections conj conn)
    conn))

(defn caught [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo ex ex)))

(defn prompt-error [connections mode]
  (let [conn (peer! connections mode)
        session-id (acp/new-session! conn)]
    (caught #(acp/prompt! conn session-id "question" 2))))

(defn stopped? [conn] (not (.isAlive ^Process (:process conn))))

(describe "通用 ACP v1 stdio 客户端"
  (with connections (atom []))
  (after (doseq [conn @@connections] (acp/close! conn)))

  (it "协商协议1，分段UTF-8与交织更新只聚合正文，两轮prompt复用同一会话"
    (let [updates (atom []) callback-done (promise)
          conn (peer! @connections "normal"
                      {:on-update #(do (swap! updates conj %)
                                       (when (= "2:second" (get-in % ["update" "content" "text"]))
                                         (deliver callback-done true)))})
          session-id (acp/new-session! conn "." 3)
          first-answer (acp/prompt! conn session-id "first" 3)
          second-answer (acp/prompt! conn session-id "second" 3)]
      (should= "fake-session" session-id)
      (should= 1 (get-in conn [:initialize "protocolVersion"]))
      (should= true (get-in conn [:capabilities "loadSession"]))
      (should= "回答🙂 1:first" (:answer first-answer))
      (should= "回答🙂 2:second" (:answer second-answer))
      (should= "end_turn" (:stopReason first-answer))
      (should= ["tool_call" "tool_call_update"]
               (mapv #(get % "sessionUpdate") (:toolEvents first-answer)))
      (should= true (deref callback-done 1000 false))
      (should= "startup message" (get-in (first @updates) ["update" "content" "text"]))
      (should-not (contains? conn :args))
      (should-not (contains? conn :env))
      (should= "fake/one" (:currentValue (first (acp/config-options conn))))
      (should= "fake/two" (:currentValue (first (acp/set-config-option! conn session-id "model" "fake/two"))))
      (should= "fake/two" (:currentValue (first (acp/config-options conn))))))

  (it "argv按字面量传递，合并env并支持nil删除变量，cwd为指定的绝对目录"
    (let [cwd (.getCanonicalPath (io/file "spec"))
          literal "$(touch injected); spaced argument"
          conn (peer! @connections "launch"
                      {:args ["python3" "-u" peer-script "launch" literal "SECRET_ARG"]
                       :env {"ACP_TEST_VALUE" "SECRET_ENV" "ACP_TEST_REMOVE" nil} :cwd cwd})
          answer (:answer (acp/prompt! conn (acp/new-session! conn) "hello"))
          data (json/read-json answer)]
      (should= cwd (get data "cwd"))
      (should= "SECRET_ENV" (get data "value"))
      (should= nil (get data "removed"))
      (should= [literal "SECRET_ARG"] (get data "argv"))))

  (it "反向permission请求统一拒绝，并拒绝未知方法；双向相同id不会错误关联"
    (doseq [mode ["reject_once" "reject_always" "permission-cancelled"]]
      (let [conn (peer! @connections mode)]
        (should= "denied" (:answer (acp/prompt! conn (acp/new-session! conn) "question"))))))

  (it "loadSession能力允许恢复会话，历史replay不混入新prompt答案"
    (let [conn (peer! @connections "normal")]
      (should= "fake-session" (acp/load-session! conn "fake-session" "." 3))
      (should= "回答🙂 1:after load" (:answer (acp/prompt! conn "fake-session" "after load")))))

  (it "未声明loadSession时返回503，连接仍可创建新会话"
    (let [conn (peer! @connections "no-load")
          error (caught #(acp/load-session! conn "fake-session" "." 3))]
      (should= 503 (:status (ex-data error)))
      (should= :load-session-unsupported (:reason (ex-data error)))
      (should= "fake-session" (acp/new-session! conn))))

  (it "普通调用并发时仍串行归属各轮更新"
    (let [conn (peer! @connections "normal") session-id (acp/new-session! conn)
          gate (promise)
          calls (mapv (fn [question] (future @gate (acp/prompt! conn session-id question))) ["one" "two"])]
      (deliver gate true)
      (let [answers (mapv #(deref % 4000 nil) calls)]
        (should (str/ends-with? (:answer (first answers)) ":one"))
        (should (str/ends-with? (:answer (second answers)) ":two"))
        (should= #{"1" "2"} (set (map #(second (re-find #" (\d):" (:answer %))) answers))))))

  (it "cancel通知可在prompt等待时并发发出，并保持连接可用"
    (let [started (promise)
          conn (peer! @connections "cancel"
                      {:on-update #(when (= "started" (get-in % ["update" "content" "text"]))
                                     (deliver started true))})
          session-id (acp/new-session! conn)
          call (future (acp/prompt! conn session-id "wait"))]
      (should= true (deref started 1000 false))
      (should= nil (acp/cancel! conn session-id))
      (should= {:answer "startedcancelled" :stopReason "cancelled" :toolEvents []}
               (deref call 2000 nil))
      (should-not (stopped? conn))))

  (it "每轮独立计算1MiB预算，持续消费stderr而不把日志混进回答"
    (let [conn (peer! @connections "stderr-per-round") session-id (acp/new-session! conn)]
      (should= "回答🙂 1:first" (:answer (acp/prompt! conn session-id "first")))
      (should= "回答🙂 2:second" (:answer (acp/prompt! conn session-id "second")))))

  (it "RPC error保留安全错误码，不泄露agent message/data/env/args"
    (let [conn (peer! @connections "rpc-error" {:env {"PRIVATE_TOKEN" "SECRET_ENV"}})
          session-id (acp/new-session! conn)
          error (caught #(acp/prompt! conn session-id "private question"))]
      (should= {:status 502 :reason :rpc-error :rpcCode -32001} (ex-data error))
      (should-not-contain "SECRET" (pr-str error))
      (should-not-contain "PRIVATE_TOKEN" (pr-str error))
      (should-not-contain "private question" (pr-str error))
      (should-not (stopped? conn))))

  (it "非法JSON、UTF-8、协议、响应id及结果结构返回502并关闭进程"
    (doseq [mode ["invalid-json" "invalid-number" "invalid-control" "invalid-utf8"
                  "invalid-protocol" "unknown-id" "ambiguous-response" "invalid-stop"]]
      (let [error (prompt-error @connections mode)]
        (should= {:status 502 :reason :invalid-protocol} (ex-data error))
        ;; fail! 先唤醒调用者，close! 与读取线程可能仍在完成进程清理。
        (.waitFor ^Process (:process (last @@connections)))
        (should (stopped? (last @@connections))))))

  (it "EOF、半帧EOF和进程提前退出均失败，helper退出125映射503"
    (doseq [[mode status] [["eof" 502] ["partial-eof" 502] ["exit7" 502] ["exit125" 503]]]
      (let [error (prompt-error @connections mode)]
        (should= status (:status (ex-data error)))
        (should-not-contain "SECRET_AGENT_CREDENTIAL" (pr-str error)))))

  (it "初始化协议不匹配或helper启动失败时清理；启动失败不包含敏感命令"
    (doseq [[mode status] [["wrong-version" 502] ["init-exit125" 503]]]
      (should= status (:status (ex-data (caught #(peer! @connections mode))))))
    (let [error (caught #(acp/open! {:args ["/not-found/SECRET_ARG"]
                                    :env {"TOKEN" "SECRET_ENV"} :cwd "." :timeout 1}))]
      (should= 503 (:status (ex-data error)))
      (should-not-contain "SECRET" (pr-str error))))

  (it "session/new返回非法session id时关闭连接"
    (let [conn (peer! @connections "invalid-session")
          error (caught #(acp/new-session! conn))]
      (should= 502 (:status (ex-data error)))
      (should (stopped? conn))))

  (it "prompt超时返回504并终止进程，后续操作立即收到失败"
    (let [conn (peer! @connections "timeout") session-id (acp/new-session! conn)
          started (System/nanoTime)
          error (caught #(acp/prompt! conn session-id "question" 0.1))]
      (should= 504 (:status (ex-data error)))
      (should (< (/ (- (System/nanoTime) started) 1e9) 2))
      (should (stopped? conn))
      (should= 504 (:status (ex-data (caught #(acp/prompt! conn session-id "again")))))))

  (it "子进程不读stdin且写入超过管道容量时仍能超时并清理"
    (let [conn (peer! @connections "blocked-input")
          started (System/nanoTime)
          error (caught #(acp/prompt! conn "fake-session" (apply str (repeat 200000 "x")) 0.1))]
      (should= 504 (:status (ex-data error)))
      (should (< (/ (- (System/nanoTime) started) 1e9) 2))
      (should (stopped? conn))))

  (it "无换行stdout、stderr和双流合计超1MiB均返回502并清理"
    (doseq [mode ["stdout-limit" "stderr-limit" "combined-limit"]]
      (let [error (prompt-error @connections mode)]
        (should= {:status 502 :reason :output-limit} (ex-data error))
        (.waitFor ^Process (:process (last @@connections)))
        (should (stopped? (last @@connections))))))

  (it "回调可发出cancel且不阻塞读线程"
    (let [holder (atom nil) cancelled (promise)
          conn (peer! @connections "cancel"
                      {:on-update #(when (= "started" (get-in % ["update" "content" "text"]))
                                     (acp/cancel! @holder "fake-session")
                                     (deliver cancelled true))})]
      (reset! holder conn)
      (should= "cancelled" (:stopReason (acp/prompt! conn (acp/new-session! conn) "question")))
      (should= true (deref cancelled 1000 false))))

  (it "慢回调的队列有界，不会堵住stdout或无限保存日志"
    (let [release (promise)
          conn (peer! @connections "callback-flood" {:on-update (fn [_] @release)})]
      (try
        (should= {:status 502 :reason :callback-queue-limit}
                 (ex-data (caught #(acp/prompt! conn (acp/new-session! conn) "question"))))
        (finally (deliver release true)))))

  (it "关闭幂等且唤醒等待prompt的调用"
    (let [started (promise)
          conn (peer! @connections "cancel"
                      {:on-update #(when (= "started" (get-in % ["update" "content" "text"]))
                                     (deliver started true))})
          session-id (acp/new-session! conn)
          call (future (caught #(acp/prompt! conn session-id "question")))]
      (should= true (deref started 1000 false))
      (should= nil (acp/close! conn))
      (should= nil (acp/close! conn))
      (should= 503 (:status (ex-data (deref call 1000 nil))))
      (should (stopped? conn))))

  (it "关闭会清理仍持有stdio的进程树"
    (let [directory (Files/createTempDirectory "acp-tree-spec-" (make-array FileAttribute 0))
          pid-file (io/file (str directory) "child.pid")]
      (try
        (let [conn (peer! @connections "tree"
                          {:args ["python3" "-u" peer-script "tree" (str pid-file)]})
              child (.get (java.lang.ProcessHandle/of (Long/parseLong (slurp pid-file))))]
          (should (.isAlive child))
          (acp/close! conn)
          (.get (.onExit child) 2 java.util.concurrent.TimeUnit/SECONDS)
          (should-not (.isAlive child))
          (should (stopped? conn)))
        (finally (.delete pid-file) (.delete (.toFile directory)))))))
