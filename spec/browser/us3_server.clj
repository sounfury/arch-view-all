;; 职责：为 US-3 浏览器集成测试启动多个真实项目 HTTP 服务；由测试进程负责终止。
(require '[arch-view.core :as core]
         '[arch-view.web.server :as server]
         '[arch-view.workspace :as workspace]
         '[arch-view.trace.store :as trace-store]
         '[arch-view.trace.verify :as trace-verify])

(doseq [root *command-line-args*]
  (let [architecture (core/load-architecture root)
        ws (workspace/create architecture root {})
        _ (trace-store/write-trace! ws
            (trace-verify/prepare ws
              {:id "us3-shared" :title "RPC 到共享集成" :question "RPC 使用哪个共享模块？"
               :steps [{:title "RPC 入口" :anchor {:path "src/demo/rpc/api/entry.clj" :startLine 1}}
                       {:title "共享集成" :anchor {:path "src/demo/integration/api/entry.clj" :startLine 1}}]}))
        app (server/start! architecture root {:port 0 :crap :off} nil)]
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (:stop app)))
    (println "US3_SERVER" (:url app))
    (flush)))
