;; 职责：用本地假 ACP 进程验证会话复用、恢复、取消和目标隔离。
;; 核心入口：sessions/ask! Speclj 示例。

(ns arch-view.context.sessions-spec
  (:require [arch-view.context.acp :as acp]
            [arch-view.context.fixtures :as fixture :refer [status-of]]
            [arch-view.context.session-store :as store]
            [arch-view.context.sessions :as sessions]
            [clojure.java.io :as io]
            [speclj.core :refer :all]))

(def peer (.getCanonicalPath (io/file "spec/arch_view/context/fixtures/acp_peer.py")))

(defn- acp-ws
  ([ws] (acp-ws ws "normal"))
  ([ws mode]
   (assoc ws :opts {:agent-provider "pi-acp"
                    :agent-command (str "python3 -u \"" peer "\" " mode)
                    :agent-timeout 8
                    :agent-state-dir (str (.resolve (:root ws) "agent-state"))
                    :pi-agent-dir (str (.resolve (:root ws) "empty-pi"))}
          :agent-sessions (sessions/create-manager))))

(defn- ask!
  ([ws question] (ask! ws "demo.web.model" question {}))
  ([ws question options] (ask! ws "demo.web.model" question options))
  ([ws target question options]
   (sessions/ask! ws target (str "上下文\n" question) question [] options)))

(describe "ACP 会话管理"
  (with saved (atom {}))
  (with ws (acp-ws (fixture/project)))
  (around [it]
    (with-redefs [store/save! (fn [_ id metadata _] (swap! @saved assoc id metadata) metadata)
                  store/restore! (fn [_ id _]
                                   (or (get @@saved id)
                                       (throw (ex-info "没有这个 AI 会话" {:status 404}))))]
      (it)))
  (after (sessions/close! (:agent-sessions @ws)) (fixture/cleanup! @ws))

  (it "同一会话连续两轮提问，本地 sessionId 稳定并聚合 ACP 正文"
    (let [first (ask! @ws "first")
          second (ask! @ws "second" {:session-id (:sessionId first)})]
      (should= (:sessionId first) (:sessionId second))
      (should (re-matches #"[0-9a-fA-F-]{36}" (:sessionId first)))
      (should= "fake-session" (:agentSessionId (get @@saved (:sessionId first))))
      (should-contain "1:上下文\nfirst" (:answer first))
      (should-contain "2:上下文\nsecond" (:answer second))
      (should= true (get-in first [:sandbox :readOnly]))
      (should= "pi-acp" (:provider first))
      (should= 2 (count (:history (get @@saved (:sessionId first)))))))

  (it "关闭进程后按已保存快照恢复，走 session/load 而不是新建"
    (let [news (atom 0) loads (atom 0)
          new! acp/new-session! load! acp/load-session!
          first (ask! @ws "first")]
      (sessions/close-session! @ws (:sessionId first))
      (with-redefs [acp/new-session! (fn [& args] (swap! news inc) (apply new! args))
                    acp/load-session! (fn [& args] (swap! loads inc) (apply load! args))]
        (let [second (ask! @ws "after load" {:session-id (:sessionId first)})]
          (should= 0 @news)
          (should= 1 @loads)
          (should= (:sessionId first) (:sessionId second))
          (should-contain "after load" (:answer second))))))

  (it "已有会话不能换到另一个目标"
    (let [first (ask! @ws "first")]
      (should= 409 (status-of #(ask! @ws "demo.web.api" "second" {:session-id (:sessionId first)})))))

  (it "作答中可以取消，随后连接不再占用"
    (let [ws (acp-ws (fixture/project) "cancel")
          started (promise) session (atom nil)
          call (future (ask! ws "wait" {:on-session #(reset! session (:sessionId %))
                                        :on-update #(when (= "started" (get-in % ["update" "content" "text"]))
                                                      (deliver started true))}))]
      (try
        (should= true (deref started 4000 false))
        (should= {:cancelled true :sessionId @session} (sessions/cancel! ws @session))
        (let [result (deref call 4000 nil)]
          (should= "cancelled" (:stopReason result))
          (should-contain "started" (:answer result)))
        (finally (sessions/close! (:agent-sessions ws)) (fixture/cleanup! ws)))))

  (it "打开会话即提供模型和思考强度选择器，可在提问前切换"
    (let [opened (sessions/ensure! @ws "demo.web.model" nil)
          changed (sessions/set-config! @ws (:sessionId opened) "model" "fake/two")]
      (should= "fake/one" (:currentValue (first (:configOptions opened))))
      (should= "fake/two" (:currentValue (first (:configOptions changed))))
      (should= "low" (:currentValue (second (:configOptions changed))))))

  (it "没有活动进程时取消返回 404"
    (should= 404 (status-of #(sessions/cancel! @ws (str (java.util.UUID/randomUUID)))))))
