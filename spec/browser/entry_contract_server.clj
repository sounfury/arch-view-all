;; 职责：为 BUG-08 浏览器回归写入独立 RPC mock 并启动真实 HTTP 服务。
(require '[arch-view.cli-support :as support]
         '[arch-view.core :as core]
         '[arch-view.entries.contract-fixture :as mock]
         '[arch-view.web.server :as server]
         '[clojure.java.io :as io])

(let [root (first *command-line-args*)]
  (doseq [[path content] mock/files]
    (let [file (io/file root path)] (io/make-parents file) (spit file content :encoding "UTF-8")))
  (support/write-json-file! (io/file root ".arch-view" "entry-rules.json") mock/rules)
  (let [app (server/start! (core/load-architecture root) root {:port 0 :crap :off} nil)]
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (:stop app)))
    (println "ENTRY_CONTRACT_SERVER" (:url app))
    (flush)))
