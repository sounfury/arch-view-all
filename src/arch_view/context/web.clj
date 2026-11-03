;; 职责：为网页提供上下文包、ACP 问答与会话接口，复用当前架构和代码锚点。
;; 核心入口：routes、stream!。

(ns arch-view.context.web
  (:require [arch-view.context.agent :as agent]
            [arch-view.context.pack :as pack]
            [arch-view.context.session-store :as store]
            [arch-view.context.sessions :as sessions]
            [clojure.string :as str]))

(defn- question [params]
  (let [text (get params "body")]
    (when (str/blank? text) (throw (ex-info "问题不能为空" {:status 400})))
    text))

(defn- options [params]
  (when-let [id (not-empty (get params "sessionId"))] {:session-id id}))

(defn stream!
  "emit 接收事件名与 JSON 数据；所有启动配置来自服务端，流里不接受命令。"
  [state params emit]
  (try
    (emit "result"
          (agent/ask! state (get params "target") (question params)
                      (assoc (options params)
                             :on-session #(emit "session" %)
                             :on-update #(emit "update" %))))
    (catch Exception ex
      (emit "error" {:error (.getMessage ex) :status (get (ex-data ex) :status 500)}))))

(def routes
  {["GET" "/api/context"]
   (fn [state params]
     (pack/build state (get params "target")
                 {:question (get params "question") :complexity (:complexity state)}))
   ["POST" "/api/ask"]
   (fn [state params]
     (agent/ask! state (get params "target") (question params) (options params)))
   ["GET" "/api/agent/sessions"]
   (fn [state params] (store/list-sessions state (get params "target")))
   ["GET" "/api/agent/session"]
   (fn [state params] (store/read-session state (get params "id")))
   ["POST" "/api/agent/open"]
   (fn [state params]
     (sessions/ensure! state (get params "target") (not-empty (get params "id"))))
   ["POST" "/api/agent/config"]
   (fn [state params]
     (sessions/set-config! state (get params "id") (get params "configId") (get params "value")))
   ["POST" "/api/agent/cancel"]
   (fn [state params] (sessions/cancel! state (get params "id")))
   ["POST" "/api/agent/close"]
   (fn [state params] (sessions/close-session! state (get params "id")))})
