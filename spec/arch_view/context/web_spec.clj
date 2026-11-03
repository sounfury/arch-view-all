;; 职责：验证网页上下文和问答路由、命令帮助与参数校验。
;; 核心入口：context 接口与 CLI Speclj 示例。

(ns arch-view.context.web-spec
  (:require [arch-view.context.agent :as agent]
            [arch-view.context.cli :as cli]
            [arch-view.context.fixtures :as fixture :refer [status-of]]
            [arch-view.context.session-store :as store]
            [arch-view.context.sessions :as sessions]
            [arch-view.context.web :as web]
            [clojure.java.io :as io]
            [speclj.core :refer :all]))

(describe "上下文网页路由与命令入口"
  (with ws (fixture/project))
  (after (fixture/cleanup! @ws))

  (it "GET context 使用网页已有复杂度与问题"
    (let [path (.getCanonicalPath (io/file (.toFile (:root @ws)) "src/demo/web/model.clj"))
          state (assoc @ws :complexity {:status "ready" :files {path [{:symbol "load-model" :line 3 :complexity 6}]}})
          result ((web/routes ["GET" "/api/context"]) state {"target" "demo.web.model" "question" "如何构建？"})]
      (should= 6 (get-in result [:data :complexity :metrics :max]))
      (should-contain "如何构建？" (:markdown result))))

  (it "POST ask 只从全局配置读命令，并把请求体作为问题"
    (let [state (assoc @ws :opts {:agent-command "cat"})
          result ((web/routes ["POST" "/api/ask"]) state
                  {"target" "demo.web.model" "body" "怎么构建？" "command" "/no-such-command"})]
      (should= "cat" (:command result))
      (should-contain "怎么构建？" (:answer result))))

  (it "POST 空问题是 400，缺少配置是 409，缺少目标是 400"
    (should= 400 (status-of #((web/routes ["POST" "/api/ask"]) @ws {"target" "demo.web.model" "body" "\n"})))
    (should= 409 (status-of #((web/routes ["POST" "/api/ask"]) @ws {"target" "demo.web.model" "body" "问题"})))
    (should= 400 (status-of #((web/routes ["GET" "/api/context"]) @ws {}))))

  (it "会话列表、读取、关闭走服务端存储，取消走活动进程"
    (let [calls (atom [])]
      (with-redefs [store/list-sessions (fn [_ target] [{:id "sid" :target target :title "t" :turnCount 1}])
                    store/read-session (fn [_ id] {:id id :history []})
                    sessions/ensure! (fn [_ target id] (swap! calls conj [:open target id]) {:sessionId "sid" :configOptions [{:id "model"}]})
                    sessions/set-config! (fn [_ id config-id value]
                                          (swap! calls conj [:config id config-id value])
                                          {:sessionId id :configOptions [{:id config-id :currentValue value}]})
                    sessions/cancel! (fn [_ id] (swap! calls conj [:cancel id]) {:cancelled true :sessionId id})
                    sessions/close-session! (fn [_ id] (swap! calls conj [:close id]) {:closed true :sessionId id})]
        (should= [{:id "sid" :target "demo.web.model" :title "t" :turnCount 1}]
                 ((web/routes ["GET" "/api/agent/sessions"]) @ws {"target" "demo.web.model"}))
        (should= {:id "sid" :history []} ((web/routes ["GET" "/api/agent/session"]) @ws {"id" "sid"}))
        (should= {:sessionId "sid" :configOptions [{:id "model"}]}
                 ((web/routes ["POST" "/api/agent/open"]) @ws {"target" "demo.web.model"}))
        (should= {:sessionId "sid" :configOptions [{:id "model" :currentValue "fake/two"}]}
                 ((web/routes ["POST" "/api/agent/config"]) @ws {"id" "sid" "configId" "model" "value" "fake/two"}))
        (should= {:cancelled true :sessionId "sid"} ((web/routes ["POST" "/api/agent/cancel"]) @ws {"id" "sid"}))
        (should= {:closed true :sessionId "sid"} ((web/routes ["POST" "/api/agent/close"]) @ws {"id" "sid"}))
        (should= [[:open "demo.web.model" nil] [:config "sid" "model" "fake/two"] [:cancel "sid"] [:close "sid"]] @calls))))

  (it "流式提问转发 session/update，并把错误变成 error 事件"
    (let [events (atom [])]
      (with-redefs [agent/ask! (fn [_ target question options]
                                 (should= "demo.web.model" target)
                                 (should= "怎么构建？" question)
                                 (should= "sid" (:session-id options))
                                 ((:on-session options) {:sessionId "sid"})
                                 ((:on-update options) {"sessionId" "upstream"
                                                        "update" {"sessionUpdate" "agent_message_chunk"}})
                                 {:answer "ok" :sessionId "sid"})]
        (web/stream! @ws {"target" "demo.web.model" "sessionId" "sid" "body" "怎么构建？"}
                     (fn [event data] (swap! events conj [event data]))))
      (should= [["session" {:sessionId "sid"}]
                ["update" {"sessionId" "upstream" "update" {"sessionUpdate" "agent_message_chunk"}}]
                ["result" {:answer "ok" :sessionId "sid"}]]
               @events))
    (let [events (atom [])]
      (web/stream! @ws {"target" "demo.web.model" "body" "\n"}
                   (fn [event data] (swap! events conj [event data])))
      (should= "error" (ffirst @events))
      (should= 400 (:status (second (first @events))))))

  (it "提供中文帮助，校验目标个数、截断参数和复杂度模式"
    (let [help (with-out-str (cli/run! ["--help"]))]
      (should-contain "用法：arch-view context" help)
      (should-contain "--crap <auto|off>" help))
    (should-throw clojure.lang.ExceptionInfo (cli/run! []))
    (should-throw clojure.lang.ExceptionInfo (cli/run! ["demo.web" "extra"]))
    (should-throw clojure.lang.ExceptionInfo (cli/run! ["demo.web" "--max-source-lines" "x"]))
    (should-throw clojure.lang.ExceptionInfo (cli/run! ["demo.web" "--crap" "always"]))))
