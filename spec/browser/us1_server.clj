;; 职责：为 US-1 浏览器集成测试启动真实多模块 Java 项目的 HTTP 服务；由测试进程负责终止。
(require '[arch-view.core :as core]
         '[arch-view.web.server :as server])

(doseq [root *command-line-args*]
  (let [architecture (core/load-architecture root)
        app (server/start! architecture root {:port 0 :crap :off} nil)]
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (:stop app)))
    (println "US1_SERVER" (:url app))
    (flush)))
